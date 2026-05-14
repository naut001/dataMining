package algorithms.lppgrowth;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import tools.MemoryLogger;

/*
 * This file is part of the SPMF DATA MINING SOFTWARE *
 * (http://www.philippe-fournier-viger.com/spmf).
 *
 * SPMF is free software: you can redistribute it and/or modify it under the *
 * terms of the GNU General Public License as published by the Free Software *
 * Foundation, either version 3 of the License, or (at your option) any later *
 * version. SPMF is distributed in the hope that it will be useful, but WITHOUT
 * ANY * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * SPMF. If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright Peng Yang  2019
 * Parallel version 2026
 */
/**
 * PARALLEL implementation of the LPP-Growth algorithm based on FP-growth
 *
 * Parallelization strategy: Song song hóa vòng lặp Header Table Loop.
 * Mỗi item trong header table được xử lý độc lập bởi một thread riêng.
 *
 * @author Peng yang (original), Parallel version 2026
 */
public class AlgoLPPGrowth_Parallel {

	/** start time of the latest execution */
	private long startTimestamp;

	/** end time of the latest execution */
	private long endTime;

	/** largest timestamp in the database */
	private int lastTimestamp = -1;

	/** number of freq. itemsets found - THREAD-SAFE với AtomicInteger */
	private AtomicInteger itemsetCount = new AtomicInteger(0);

	/** object to write the output file */
	BufferedWriter writer = null;

	/** Queue để lưu kết quả từ các threads - THREAD-SAFE */
	private ConcurrentLinkedQueue<String> resultQueue = new ConcurrentLinkedQueue<>();

	/**
	 * The patterns that are found (if the user want to keep them into memory)
	 */
	protected Itemsets patterns = null;

	/**
	 * This variable is used to determine the size of buffers to store itemsets. A
	 * value of 50 is enough because it allows up to 2^50 patterns!
	 */
	final int BUFFERS_SIZE = 2000;

	/** maximum pattern length */
	private int maxPatternLength = 1000;

	/**
	 * whether the timestamps need self increment as step of 1 for each transcation
	 * Default is true
	 */
	private boolean selfIncrement;

	/** the minimum duration threshold. */
	private int minDur;

	/** the maximum periodicity threshold. */
	private int maxPer;

	/** the maxSoPer **/
	private int maxSoPer;

	/** Số lượng threads sử dụng */
	private int numThreads;

	/**
	 * Constructor
	 */
	public AlgoLPPGrowth_Parallel() {
		// Mặc định sử dụng số lượng processors có sẵn
		this.numThreads = Runtime.getRuntime().availableProcessors();
	}

	/**
	 * Constructor với số threads tùy chỉnh
	 */
	public AlgoLPPGrowth_Parallel(int numThreads) {
		this.numThreads = numThreads;
	}

	/**
	 * Method to run the LPPGrowth algorithm in PARALLEL.
	 *
	 * @param input  the path to an input file containing a transaction database.
	 * @param output the output file path for saving the result (if null, the result
	 *               will be returned by the method instead of being saved).
	 * @return the result if no output file path is provided.
	 * @throws IOException exception if error reading or writing files
	 */
	public Itemsets runAlgorithm(String input, String output, int maxPer, int minDur, int maxSoPer,
			boolean selfIncrement) throws IOException {
		// record start time
		startTimestamp = System.currentTimeMillis();

		// reset counter
		itemsetCount.set(0);
		resultQueue.clear();

		// initialize tool to record memory usage
		MemoryLogger.getInstance().reset();
		MemoryLogger.getInstance().checkMemory();

		this.minDur = minDur;
		this.maxPer = maxPer;
		this.selfIncrement = selfIncrement;
		this.maxSoPer = maxSoPer;

		// if the user want to keep the result into memory
		if (output == null) {
			writer = null;
			patterns = new Itemsets("Local Periodic Pattern");
		} else {
			patterns = null;
			writer = new BufferedWriter(new FileWriter(output));
		}

		// (1) PREPROCESSING: Initial database scan to determine the time-interval of
		// each item
		final Map<Integer, TimeIntervals> mapTimeIntervals = scanDatabaseToDetermineTimeIntervalsOfSingleItems(input);

		// (2) Scan the database again to build the initial PFTI-Tree
		LPPTree tree = new LPPTree();
		buildTreeByScanDataAgain(tree, input, mapTimeIntervals);

		// (3) PARALLEL MINING: Song song hóa việc xử lý các item trong header table
		if (tree.headerList.size() > 0) {
			// Tạo ExecutorService với số threads đã cấu hình
			ExecutorService executor = Executors.newFixedThreadPool(numThreads);

			System.out.println("[PARALLEL] Sử dụng " + numThreads + " threads để xử lý "
				+ tree.headerList.size() + " items trong header table");

			// ===== SONG SONG HÓA: Mỗi item trong header table = 1 task độc lập =====
			// Duyệt ngược từ cuối header list (giống bản tuần tự)
			for (int i = tree.headerList.size() - 1; i >= 0; i--) {
				final Integer item = tree.headerList.get(i);
				final LPPTree treeSnapshot = tree; // Tham chiếu đến tree hiện tại
				final Map<Integer, TimeIntervals> mapSnapshot = mapTimeIntervals;

				// Submit task cho thread pool
				executor.submit(() -> {
					try {
						// Mỗi thread xử lý 1 item: tạo conditional tree + đệ quy
						processHeaderItem(item, treeSnapshot, mapSnapshot);
					} catch (IOException e) {
						e.printStackTrace();
					}
				});
			}

			// ===== SHUTDOWN: Đợi tất cả threads hoàn thành =====
			executor.shutdown();
			try {
				// Đợi tối đa 60 phút
				if (!executor.awaitTermination(60, TimeUnit.MINUTES)) {
					System.err.println("[WARNING] Executor timeout - forcing shutdown");
					executor.shutdownNow();
				}
			} catch (InterruptedException e) {
				System.err.println("[ERROR] Executor interrupted");
				executor.shutdownNow();
				Thread.currentThread().interrupt();
			}

			System.out.println("[PARALLEL] Tất cả threads đã hoàn thành");
		}

		// (4) Ghi kết quả từ queue ra file (nếu có)
		if (writer != null) {
			for (String result : resultQueue) {
				writer.write(result);
				writer.newLine();
			}
			writer.close();
		}

		// record the execution end time (SAU KHI tất cả threads xong)
		endTime = System.currentTimeMillis();

		// check the memory usage
		MemoryLogger.getInstance().checkMemory();

		// return the result (if saved to memory)
		return patterns;
	}

	/**
	 * Xử lý một item trong header table (được gọi bởi mỗi thread)
	 *
	 * @param item item cần xử lý
	 * @param tree LPPTree hiện tại
	 * @param mapTimeIntervals map time intervals
	 * @throws IOException
	 */
	private void processHeaderItem(Integer item, LPPTree tree, Map<Integer, TimeIntervals> mapTimeIntervals)
			throws IOException {

		// Tạo buffer riêng cho thread này (tránh race condition)
		int[] itemsetBuffer = new int[BUFFERS_SIZE];
		itemsetBuffer[0] = item;

		// Save pattern cho item này
		saveItemset(itemsetBuffer, 1, mapTimeIntervals.get(item).intervals);

		// === (A) Construct beta's prefix tree ===
		List<List<LPPNode>> prefixPaths = new ArrayList<>();
		LPPNode path = tree.mapItemNodes.get(item);

		// Map to count the timestamps of items in the conditional prefix tree
		Map<Integer, List<Integer>> mapTimestampsBeta = new HashMap<>();

		while (path != null) {
			if (path.parent.itemID != -1) {
				List<LPPNode> prefixPath = new ArrayList<>();
				prefixPath.add(path);

				List<Integer> pathTimestamps = path.timestamps;
				LPPNode parent = path.parent;

				while (parent.itemID != -1) {
					prefixPath.add(parent);

					if (mapTimestampsBeta.get(parent.itemID) == null) {
						mapTimestampsBeta.put(parent.itemID, new ArrayList<>(pathTimestamps));
					} else {
						mapTimestampsBeta.get(parent.itemID).addAll(pathTimestamps);
					}
					parent = parent.parent;
				}
				prefixPaths.add(prefixPath);
			}
			path = path.nodeLink;
		}

		// convert beta's timestamps to time-intervals
		Map<Integer, TimeIntervals> mapBetaTimeIntervals = getMapBetaTimeIntervals(mapTimestampsBeta);

		// header table have pattern that has time-interval
		if (mapBetaTimeIntervals.size() > 0) {
			// (B) Construct beta's conditional PFTI-Tree
			LPPTree treeBeta = new LPPTree();
			for (List<LPPNode> prefixPath : prefixPaths) {
				treeBeta.addPrefixPath(prefixPath, mapBetaTimeIntervals);
			}

			// Mine recursively the Beta tree if the root has child(s)
			if (treeBeta.root.childs.size() > 0) {
				treeBeta.createHeaderList(null, mapBetaTimeIntervals);
				// Gọi đệ quy (tuần tự trong mỗi nhánh)
				pftiGrowth(treeBeta, itemsetBuffer, 1, mapBetaTimeIntervals);
			}
		}
	}

	/**
	 * The main method of this algorithm to mine an LPPTree (RECURSIVE - chạy tuần tự trong mỗi nhánh)
	 *
	 * @param tree             an LPPTree
	 * @param prefix           the prefix of the current itemset
	 * @param prefixLength     the prefix length
	 * @param mapTimeIntervals a map of time intervals of items
	 * @throws IOException if error while reading or writing files
	 */
	@SuppressWarnings("serial")
	private void pftiGrowth(LPPTree tree, int[] prefix, int prefixLength, Map<Integer, TimeIntervals> mapTimeIntervals)
			throws IOException {
		if (prefixLength == maxPatternLength) {
			return;
		}

		// For each item in the header table list of the tree in reverse order.
		while (tree.headerList.size() > 0) {
			Integer item = tree.headerList.get(tree.headerList.size() - 1);

			prefix[prefixLength] = item;

			// save beta to the output file
			saveItemset(prefix, prefixLength + 1, mapTimeIntervals.get(item).intervals);

			if (prefixLength + 1 < maxPatternLength) {

				List<List<LPPNode>> prefixPaths = new ArrayList<>();
				LPPNode path = tree.mapItemNodes.get(item);

				Map<Integer, List<Integer>> mapTimestampsBeta = new HashMap<>();

				while (path != null) {
					if (path.parent.itemID != -1) {
						List<LPPNode> prefixPath = new ArrayList<>();
						prefixPath.add(path);

						List<Integer> pathTimestamps = path.timestamps;
						LPPNode parent = path.parent;

						while (parent.itemID != -1) {
							prefixPath.add(parent);

							if (mapTimestampsBeta.get(parent.itemID) == null) {
								mapTimestampsBeta.put(parent.itemID, new ArrayList<Integer>() {
									{
										addAll(pathTimestamps);
									}
								});
							} else {
								mapTimestampsBeta.get(parent.itemID).addAll(pathTimestamps);
							}
							parent = parent.parent;
						}
						prefixPaths.add(prefixPath);
					}
					path = path.nodeLink;
				}

				tree.removeTailItem();

				Map<Integer, TimeIntervals> mapBetaTimeIntervals = getMapBetaTimeIntervals(mapTimestampsBeta);

				if (mapBetaTimeIntervals.size() > 0) {
					LPPTree treeBeta = new LPPTree();
					for (List<LPPNode> prefixPath : prefixPaths) {
						treeBeta.addPrefixPath(prefixPath, mapBetaTimeIntervals);
					}

					if (treeBeta.root.childs.size() > 0) {
						treeBeta.createHeaderList(tree.headerList, mapBetaTimeIntervals);
						pftiGrowth(treeBeta, prefix, prefixLength + 1, mapBetaTimeIntervals);
					}
				}
			}
		}

		MemoryLogger.getInstance().checkMemory();
	}

	/**
	 * Scan database to determine the periodic time-intervals of single items.
	 *
	 * @param input the path of database
	 * @return a map of items to time intervals
	 * @throws IOException if error while readin the input file
	 */
	private Map<Integer, TimeIntervals> scanDatabaseToDetermineTimeIntervalsOfSingleItems(String input)
			throws IOException {
		BufferedReader reader = new BufferedReader(new FileReader(input));
		String line;

		Map<Integer, TimeIntervals> mapTimeIntervals = new HashMap<>();
		Map<Integer, Integer> preTimestamp = new HashMap<>();
		Map<Integer, Integer> soPer = new HashMap<>();

		if (selfIncrement) {
			int ts = 1;
			while (((line = reader.readLine()) != null)) {
				if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '%' || line.charAt(0) == '@') {
					continue;
				}
				String[] lineSplited = line.split(" ");

				for (String itemString : lineSplited) {
					Integer itemName = Integer.parseInt(itemString);

					if (mapTimeIntervals.containsKey(itemName)) {
						int pre_ts = preTimestamp.get(itemName);
						int per = ts - pre_ts;

						if (per == 0)
							continue;

						TimeIntervals timeIntervals = mapTimeIntervals.get(itemName);

						if (per <= maxPer && timeIntervals.left == -1) {
							timeIntervals.left = pre_ts;
							soPer.put(itemName, maxSoPer);
						}

						if (timeIntervals.left != -1) {
							soPer.put(itemName, Math.max(0, soPer.get(itemName) + (per - maxPer)));
							if (soPer.get(itemName) > maxSoPer) {
								if (pre_ts - timeIntervals.left >= minDur) {
									timeIntervals.addTimeInterval(pre_ts);
								}
								timeIntervals.left = -1;
							}
						}

					} else {
						mapTimeIntervals.put(itemName, new TimeIntervals());
					}
					preTimestamp.put(itemName, ts);
				}
				ts++;
			}
			lastTimestamp = ts - 1;

		} else {
			int ts = -1;
			while (((line = reader.readLine()) != null)) {
				if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '%' || line.charAt(0) == '@') {
					continue;
				}

				String[] lineSplited = line.split("\\|");
				String[] lineItems = lineSplited[0].split(" ");
				ts = Integer.parseInt(lineSplited[1]);
				for (String itemString : lineItems) {
					Integer itemName = Integer.parseInt(itemString);

					if (preTimestamp.containsKey(itemName)) {
						int preTS = preTimestamp.get(itemName);
						int per = ts - preTS;

						TimeIntervals timeIntervals = mapTimeIntervals.get(itemName);

						if (per <= maxPer && timeIntervals.left == -1) {
							timeIntervals.left = preTS;
							soPer.put(itemName, maxSoPer);
						}

						if (timeIntervals.left != -1) {
							soPer.put(itemName, Math.max(0, soPer.get(itemName) + (per - maxPer)));
							if (soPer.get(itemName) > maxSoPer) {
								if (preTS - timeIntervals.left >= minDur) {
									timeIntervals.addTimeInterval(preTS);
								}
								timeIntervals.left = -1;
							}
						}

					} else {
						mapTimeIntervals.put(itemName, new TimeIntervals());
					}
					preTimestamp.put(itemName, ts);
				}
			}
			lastTimestamp = ts;
		}
		reader.close();

		// Deal with the last timestamp
		Iterator<Map.Entry<Integer, TimeIntervals>> it = mapTimeIntervals.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, TimeIntervals> entry = it.next();

			if (entry.getValue().left != -1) {
				soPer.put(entry.getKey(), Math.max(0,
						soPer.get(entry.getKey()) + (lastTimestamp - preTimestamp.get(entry.getKey()) - maxPer)));

				if (soPer.get(entry.getKey()) <= maxSoPer && lastTimestamp - entry.getValue().left >= minDur) {
					entry.getValue().addTimeInterval(lastTimestamp);
				}

				if (soPer.get(entry.getKey()) > maxSoPer
						&& preTimestamp.get(entry.getKey()) - entry.getValue().left >= minDur) {
					entry.getValue().addTimeInterval(preTimestamp.get(entry.getKey()));
				}
				entry.getValue().left = -1;
			}

			if (entry.getValue().intervals.size() <= 0) {
				it.remove();
			}
		}

		return mapTimeIntervals;
	}

	/**
	 * Build a tree by scanning the database again
	 *
	 * @param tree             a new tree
	 * @param input            the input path
	 * @param mapTimeIntervals the items' periodic time-intervals
	 * @throws IOException if error while reading or writing to file
	 */
	private void buildTreeByScanDataAgain(LPPTree tree, String input, Map<Integer, TimeIntervals> mapTimeIntervals)
			throws IOException {
		BufferedReader reader = new BufferedReader(new FileReader(input));
		String line;

		if (selfIncrement) {
			int ts = 1;

			while (((line = reader.readLine()) != null)) {
				if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '%' || line.charAt(0) == '@') {
					continue;
				}

				String[] lineSplited = line.trim().split(" ");
				List<Integer> transaction = new ArrayList<>();

				for (String itemString : lineSplited) {
					Integer itemName = Integer.parseInt(itemString);

					if (mapTimeIntervals.containsKey(itemName) && mapTimeIntervals.get(itemName).isInside(ts)
							&& !transaction.contains(itemName)) {
						transaction.add(itemName);
					}
				}

				Collections.sort(transaction, new Comparator<Integer>() {
					public int compare(Integer item1, Integer item2) {
						int compare = mapTimeIntervals.get(item2).getTotalDuration()
								- mapTimeIntervals.get(item1).getTotalDuration();
						if (compare == 0) {
							return (item1 - item2);
						}
						return compare;
					}
				});

				tree.addTransaction(transaction, ts);
				ts++;
			}

		} else {
			int ts = -1;

			while (((line = reader.readLine()) != null)) {
				if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '%' || line.charAt(0) == '@') {
					continue;
				}

				String[] lineSplited = line.trim().split("\\|");
				String[] lineItems = lineSplited[0].trim().split(" ");
				ts = Integer.parseInt(lineSplited[1]);

				List<Integer> transaction = new ArrayList<>();

				for (String itemString : lineItems) {
					Integer itemName = Integer.parseInt(itemString);

					if (mapTimeIntervals.containsKey(itemName) && mapTimeIntervals.get(itemName).isInside(ts)
							&& !transaction.contains(itemName)) {
						transaction.add(itemName);
					}
				}

				Collections.sort(transaction, new Comparator<Integer>() {
					public int compare(Integer item1, Integer item2) {
						int compare = mapTimeIntervals.get(item2).getTotalDuration()
								- mapTimeIntervals.get(item1).getTotalDuration();
						if (compare == 0) {
							return (item1 - item2);
						}
						return compare;
					}
				});

				if (transaction.size() > 0) {
					tree.addTransaction(transaction, ts);
				}
			}
		}

		reader.close();
		tree.createHeaderList(null, mapTimeIntervals);
	}

	/**
	 * Convert beta's timestamps to time-intervals
	 *
	 * @param mapTimestampsBeta A map of items to timestamps
	 * @return A map of items to time intervals
	 */
	private Map<Integer, TimeIntervals> getMapBetaTimeIntervals(Map<Integer, List<Integer>> mapTimestampsBeta) {

		Map<Integer, TimeIntervals> mapBetaTimeIntervals = new HashMap<>();
		Map<Integer, Integer> soPer = new HashMap<>();

		for (Map.Entry<Integer, List<Integer>> entry : mapTimestampsBeta.entrySet()) {

			TimeIntervals timeIntervals = new TimeIntervals();
			List<Integer> timestamps = entry.getValue();
			Collections.sort(timestamps);

			int preTS = -1;
			for (int timestamp : timestamps) {
				if (preTS != -1) {
					int per = timestamp - preTS;

					if (per <= maxPer && timeIntervals.left == -1) {
						timeIntervals.left = preTS;
						soPer.put(entry.getKey(), maxSoPer);
					}

					if (timeIntervals.left != -1) {
						soPer.put(entry.getKey(), Math.max(0, soPer.get(entry.getKey()) + (per - maxPer)));
						if (soPer.get(entry.getKey()) > maxSoPer) {
							if (preTS - timeIntervals.left >= minDur) {
								timeIntervals.addTimeInterval(preTS);
							}
							timeIntervals.left = -1;
						}
					}
				}
				preTS = timestamp;
			}

			if (timeIntervals.left != -1) {
				soPer.put(entry.getKey(), Math.max(0, soPer.get(entry.getKey()) + (lastTimestamp - preTS - maxPer)));
				if (soPer.get(entry.getKey()) <= maxSoPer && lastTimestamp - timeIntervals.left >= minDur) {
					timeIntervals.addTimeInterval(lastTimestamp);
				}
				if (soPer.get(entry.getKey()) > maxSoPer && preTS - timeIntervals.left >= minDur) {
					timeIntervals.addTimeInterval(preTS);
				}
			}

			if (timeIntervals.intervals.size() > 0) {
				mapBetaTimeIntervals.put(entry.getKey(), timeIntervals);
			}
		}
		mapTimestampsBeta.clear();

		return mapBetaTimeIntervals;
	}

	/**
	 * Save itemset to the results - THREAD-SAFE
	 *
	 * @param itemset       the name of itemset
	 * @param itemsetLength the size of itemset
	 * @param timeIntervals the periodic time-interval of itemset
	 * @throws IOException if error while writing to the output file
	 */
	private void saveItemset(int[] itemset, int itemsetLength, List<int[]> timeIntervals) throws IOException {

		// Tăng counter (thread-safe)
		itemsetCount.incrementAndGet();

		// if the result should be saved to a file
		if (writer != null) {
			// Tạo buffer riêng cho thread này
			int[] itemsetOutputBuffer = new int[BUFFERS_SIZE];
			System.arraycopy(itemset, 0, itemsetOutputBuffer, 0, itemsetLength);
			Arrays.sort(itemsetOutputBuffer, 0, itemsetLength);

			StringBuilder buffer = new StringBuilder();
			for (int i = 0; i < itemsetLength; i++) {
				buffer.append(itemsetOutputBuffer[i]);
				if (i != itemsetLength - 1) {
					buffer.append(' ');
				}
			}
			buffer.append(" #TIME-INTERVALS: ");
			for (int[] timeInterval : timeIntervals) {
				buffer.append("[");
				buffer.append(timeInterval[0]);
				buffer.append(",");
				buffer.append(timeInterval[1]);
				buffer.append("]   ");
			}

			// Thêm vào queue (thread-safe) thay vì ghi trực tiếp
			resultQueue.add(buffer.toString());

		} else {
			// Lưu vào memory (cần synchronized vì patterns không thread-safe)
			int[] itemsetArray = new int[itemsetLength];
			System.arraycopy(itemset, 0, itemsetArray, 0, itemsetLength);
			Arrays.sort(itemsetArray);

			Itemset itemsetObj = new Itemset(itemsetArray, timeIntervals);

			// Synchronized để tránh race condition khi add vào patterns
			synchronized (patterns) {
				patterns.addItemset(itemsetObj, itemsetLength);
			}
		}
	}

	/**
	 * Print statistics about the algorithm execution to System.out.
	 */
	public void printStats() {
		System.out.println("=============  LPP-Growth PARALLEL - STATS ===============");
		long temps = endTime - startTimestamp;
		System.out.println(" Threads used: " + numThreads);
		System.out.print(" Max memory usage: " + MemoryLogger.getInstance().getMaxMemory() + " mb \n");
		System.out.println(" Itemset counts : " + itemsetCount.get());
		System.out.println(" Total time ~ " + temps + " ms");
		System.out.println("===================================================");
	}

	/**
	 * Set the maximum pattern length
	 *
	 * @param length the maximum length
	 */
	public void setMaximumPatternLength(int length) {
		maxPatternLength = length;
	}

	/**
	 * Desactivate self-increment for transaction timestamps.
	 */
	public void cancelSelfIncrement() {
		this.selfIncrement = false;
	}

}
