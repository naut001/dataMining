package algorithms.lppm;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import tools.MemoryLogger;

/*
 * Copyright (c) 2019 Peng Yang, Philippe Fournier-Viger et al.

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
 */

/**
 * PARALLEL implementation of the LPPM-breadth algorithm based on AprioriTID
 *
 * Parallelization strategy: Level-wise synchronization + song song hóa prefix groups.
 * Ở mỗi level k, các prefix groups được chia cho các threads xử lý song song.
 * Sử dụng invokeAll() để đảm bảo tất cả threads hoàn thành level k trước khi sang level k+1.
 *
 * @author Peng yang (original), Parallel version 2026
 */

public class AlgoLPPMBreadth2_Parallel {
	/** the maximum periodicity threshold */
	private int maxPer;

	/** the minimum duration threshold */
	private int minDur;

	/** the maximal spillover of periods threshold */
	private int maxSoPer;

	/** object to write the output file */
	BufferedWriter writer = null;

	/** number of candidates - THREAD-SAFE */
	private AtomicLong intersectionCount = new AtomicLong(0);

	/**
	 * The patterns that are found (if the user want to keep them into memory)
	 */
	protected Itemsets patterns = null;

	/** the largest timestamps of the database */
	private int largestTs;

	/**
	 * if selfIncrement == true --> considering that all transactions in this
	 * database are occurring at a fixed time interval, we have assigned
	 * timestamps for each transaction as increments of 1.
	 */
	private boolean selfIncrement;

	/** number of LPPs found - THREAD-SAFE */
	private AtomicInteger itemsetCount = new AtomicInteger(0);

	/** start time of the latest execution */
	private long startTimestamp;

	/** end time of the latest execution */
	private long endTime;

	/** Queue để lưu kết quả từ các threads - THREAD-SAFE */
	private ConcurrentLinkedQueue<String> resultQueue = new ConcurrentLinkedQueue<>();

	/** Số lượng threads sử dụng */
	private int numThreads;

	/**
	 * Constructor
	 */
	public AlgoLPPMBreadth2_Parallel() {
		this.numThreads = Runtime.getRuntime().availableProcessors();
	}

	/**
	 * Constructor với số threads tùy chỉnh
	 */
	public AlgoLPPMBreadth2_Parallel(int numThreads) {
		this.numThreads = numThreads;
	}

	/**
	 * Method to run the LPPM-breadth algorithm in PARALLEL.
	 *
	 * @param input         the path to an input file containing a transaction database.
	 * @param output        the output file path for saving the result
	 * @param maxPer        the maximum periodicity threshold
	 * @param minDur        the minimum duration threshold
	 * @param maxSoPer      the maximum spillover of period threshold
	 * @param selfIncrement whether the database contains real timestamps
	 * @return the result if no output file path is provided.
	 * @throws IOException exception if error reading or writing files
	 */
	public Itemsets runAlgorithm(String input, String output, int maxPer, int minDur, int maxSoPer,
			boolean selfIncrement) throws IOException {

		// Reset the tool to assess the maximum memory usage
		MemoryLogger.getInstance().reset();

		this.maxPer = maxPer;
		this.minDur = minDur;
		this.maxSoPer = maxSoPer;
		this.selfIncrement = selfIncrement;

		itemsetCount.set(0);
		intersectionCount.set(0);
		resultQueue.clear();

		// if the user want to keep the result into memory
		if (output == null) {
			writer = null;
			patterns = new Itemsets("Local Periodic Pattern");
		} else {
			patterns = null;
			writer = new BufferedWriter(new FileWriter(output));
		}

		// record itemCount
		// record the start time
		startTimestamp = System.currentTimeMillis();

		// (1) PREPROCESSING: scan the database to build the convertTimeStamps
		Map<Integer, BitSet> mapItemTS = convertTimeStamps(input);

		// (2) generate periodic frequent pattern of 1-pattern
		ArrayList<Integer> lpp1 = new ArrayList<>();

		Iterator<Map.Entry<Integer, BitSet>> it = mapItemTS.entrySet().iterator();

		while (it.hasNext()) {
			Map.Entry<Integer, BitSet> entry = it.next();
			if (!generatePattern(entry, 1)) {
				it.remove();
			} else {
				lpp1.add(entry.getKey());
			}
		}

		// sort itemsets of size 1 according to lexicographical order.
		Collections.sort(lpp1);

		System.out.println("[PARALLEL] Sử dụng " + numThreads + " threads với level-wise synchronization");
		System.out.println("[PARALLEL] Level 1: " + lpp1.size() + " frequent items");

		// generaete candidates of size 2 by using SPM strategy:
		// key : prefix, value: the list of last item of candidates with same prefix
		LinkedHashMap<int[], ArrayList<Integer>> combinationMap = generateCandidate2(lpp1, mapItemTS);

		// ===== SONG SONG HÓA: Level-wise BFS với synchronization =====
		int level = 2;
		ExecutorService executor = Executors.newFixedThreadPool(numThreads);

		while (true) {

			combinationMap = generateCandidateKParallel(combinationMap, mapItemTS, executor, level);

			if (combinationMap.size() <= 0)
				break;

			level++;
		}

		// ===== SHUTDOWN: Đợi tất cả threads hoàn thành =====
		executor.shutdown();
		try {
			if (!executor.awaitTermination(60, TimeUnit.MINUTES)) {
				System.err.println("[WARNING] Executor timeout - forcing shutdown");
				executor.shutdownNow();
			}
		} catch (InterruptedException e) {
			System.err.println("[ERROR] Executor interrupted");
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}

		System.out.println("[PARALLEL] Tất cả levels đã hoàn thành");

		// close the output file if the result was saved to a file
		if (writer != null) {
			for (String result : resultQueue) {
				writer.write(result);
				writer.newLine();
			}
			writer.close();
		}

		// we check the memory usage
		MemoryLogger.getInstance().checkMemory();

		// record the end time for statistics (SAU KHI tất cả threads xong)
		endTime = System.currentTimeMillis();
		return patterns;

	}

	/**
	 * Convert transaction database to vertical database
	 *
	 * @param input the path to a transaction database file
	 * @return the vertical database as a map where key = item and value = bitset of timestamps
	 * @throws IOException if error while reading the file
	 */
	private Map<Integer, BitSet> convertTimeStamps(String input) throws IOException {
		BufferedReader reader = new BufferedReader(new FileReader(input));
		String line;
		Map<Integer, BitSet> mapItemTS = new HashMap<>();

		if (selfIncrement) {
			int ts = 1;
			while (((line = reader.readLine()) != null)) {
				if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '%' || line.charAt(0) == '@') {
					continue;
				}
				String[] lineSplited = line.split(" ");

				for (String itemString : lineSplited) {
					Integer itemName = Integer.parseInt(itemString);

					if (!mapItemTS.containsKey(itemName)) {
						mapItemTS.put(itemName, new BitSet());
					}
					mapItemTS.get(itemName).set(ts);
				}
				ts++;
			}
			largestTs = ts - 1;
		} else {
			int ts = 0;
			while (((line = reader.readLine()) != null)) {
				if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '%' || line.charAt(0) == '@') {
					continue;
				}

				String[] lineSplited = line.split("\\|");
				String[] lineItems = lineSplited[0].split(" ");
				ts = Integer.parseInt(lineSplited[1]);
				for (String itemString : lineItems) {
					Integer itemName = Integer.parseInt(itemString);

					if (!mapItemTS.containsKey(itemName)) {
						mapItemTS.put(itemName, new BitSet());
					}
					mapItemTS.get(itemName).set(ts);
				}
			}
			largestTs = ts;
		}

		reader.close();
		return mapItemTS;

	}

	/**
	 * Method to check whether an item can be an LPP of size 1
	 *
	 * @param entry a map entry where key = item and value = bitset of timestamps
	 * @param k     the size of the itemset
	 * @return true if it is an LPP, otherwise, false
	 * @throws IOException if error while reading or writing to file
	 */
	private boolean generatePattern(Map.Entry<Integer, BitSet> entry, int k) throws IOException {
		ArrayList<int[]> timeIntervals = new ArrayList<>();
		int left = -1;
		BitSet bitSet = entry.getValue();
		int soPer = maxSoPer;

		int preTS = bitSet.nextSetBit(1);
		int ts = bitSet.nextSetBit(preTS + 1);
		while (ts > 0) {
			if (ts - preTS <= maxPer && left == -1) {
				left = preTS;
				soPer = maxSoPer;
			}

			if (left != -1) {
				soPer = Math.max(0, soPer + ts - preTS - maxPer);
				if (soPer > maxSoPer) {
					if (preTS - left >= minDur) {
						timeIntervals.add(new int[] { left, preTS });
					} else {
						bitSet.clear(left, preTS + 1);
					}
					left = -1;
				}

			} else {
				bitSet.clear(preTS);
			}

			preTS = ts;
			ts = bitSet.nextSetBit(preTS + 1);
		}

		// add final time point
		if (left != -1) {
			soPer = Math.max(0, soPer + largestTs - preTS - maxPer);
			if (soPer > maxSoPer) {
				if (preTS - left >= minDur) {
					timeIntervals.add(new int[] { left, preTS });
				} else {
					bitSet.clear(left, preTS + 1);
				}
			} else {
				if (largestTs - left >= minDur) {
					timeIntervals.add(new int[] { left, largestTs });
				} else {
					bitSet.clear(left, largestTs + 1);
				}
			}
		}

		if (timeIntervals.size() > 0) {
			save(new int[] { entry.getKey() }, timeIntervals, k);
			entry.setValue(bitSet);
			return true;
		}
		return false;
	}

	/**
	 * Generate candidates of size 2
	 *
	 * @param lpp1      LPPs of size 1
	 * @param mapItemTS their vertical binary timestamps
	 * @return a linked hash map of an itemset as key and items that can be combined as value
	 * @throws IOException if error while reading or writing to file
	 */
	@SuppressWarnings("serial")
	private LinkedHashMap<int[], ArrayList<Integer>> generateCandidate2(ArrayList<Integer> lpp1,
			Map<Integer, BitSet> mapItemTS) throws IOException {
		LinkedHashMap<int[], ArrayList<Integer>> newConbinationMap = new LinkedHashMap<>();
		for (int i = 0; i < lpp1.size() - 1; i++) {
			int itemI = lpp1.get(i);
			BitSet bitSetI = mapItemTS.get(itemI);
			int[] head = new int[] { itemI };

			for (int j = i + 1; j < lpp1.size(); j++) {
				int itemJ = lpp1.get(j);

				BitSet bitSetIJ = (BitSet) mapItemTS.get(itemJ).clone();
				bitSetIJ.and(bitSetI);
				intersectionCount.incrementAndGet();

				ArrayList<int[]> timeIntervals = bitset2intervals(bitSetIJ);
				if (timeIntervals.size() > 0) {

					int[] itemName = new int[] { itemI, itemJ };
					save(itemName, timeIntervals, 2);

					if (!newConbinationMap.containsKey(head)) {
						newConbinationMap.put(head, new ArrayList<Integer>() {
							{
								add(itemJ);
							}
						});
					} else {
						newConbinationMap.get(head).add(itemJ);
					}
				}
			}
		}
		MemoryLogger.getInstance().checkMemory();
		return newConbinationMap;
	}

	/**
	 * Generate candidates of size k in PARALLEL với level-wise synchronization
	 *
	 * @param combinationMap the map store LPPs of size k-1
	 * @param mapItemTS      the items' timestamps (binary vector)
	 * @param executor       the ExecutorService
	 * @param level          current level k
	 * @return a linked hashmap of candidates where key = itemset, value = list of items that are combined
	 * @throws IOException if error while reading or writing to a file
	 */
	@SuppressWarnings("serial")
	private LinkedHashMap<int[], ArrayList<Integer>> generateCandidateKParallel(
			LinkedHashMap<int[], ArrayList<Integer>> combinationMap, Map<Integer, BitSet> mapItemTS,
			ExecutorService executor, int level) throws IOException {

		System.out.println("[PARALLEL] Level " + level + ": Xử lý " + combinationMap.size() + " prefix groups");

		// ===== SONG SONG HÓA: Chia các prefix groups cho các threads =====
		// Tạo danh sách các tasks - mỗi task xử lý 1 prefix group
		List<Callable<LinkedHashMap<int[], ArrayList<Integer>>>> tasks = new ArrayList<>();

		for (Map.Entry<int[], ArrayList<Integer>> entry : combinationMap.entrySet()) {
			final int[] prefixKey = entry.getKey();
			final ArrayList<Integer> suffixItems = entry.getValue();

			// Tạo task cho prefix group này
			Callable<LinkedHashMap<int[], ArrayList<Integer>>> task = () -> {
				return processPrefixGroup(prefixKey, suffixItems, mapItemTS);
			};

			tasks.add(task);
		}

		// ===== LEVEL-WISE SYNCHRONIZATION: Đợi tất cả prefix groups ở level này hoàn thành =====
		LinkedHashMap<int[], ArrayList<Integer>> newCombinationMap = new LinkedHashMap<>();

		try {
			// invokeAll() đảm bảo tất cả tasks hoàn thành trước khi trả về
			executor.invokeAll(tasks).forEach(future -> {
				try {
					LinkedHashMap<int[], ArrayList<Integer>> result = future.get();
					// Merge kết quả từ task này vào newCombinationMap
					newCombinationMap.putAll(result);
				} catch (Exception e) {
					e.printStackTrace();
				}
			});
		} catch (InterruptedException e) {
			System.err.println("[ERROR] invokeAll interrupted");
			Thread.currentThread().interrupt();
		}

		MemoryLogger.getInstance().checkMemory();
		return newCombinationMap;
	}

	/**
	 * Xử lý một prefix group (được gọi bởi mỗi thread)
	 *
	 * @param prefixKey  prefix của group này
	 * @param suffixItems danh sách items có cùng prefix
	 * @param mapItemTS  map item -> bitset
	 * @return LinkedHashMap chứa các candidates mới từ group này
	 * @throws IOException
	 */
	@SuppressWarnings("serial")
	private LinkedHashMap<int[], ArrayList<Integer>> processPrefixGroup(int[] prefixKey,
			ArrayList<Integer> suffixItems, Map<Integer, BitSet> mapItemTS) throws IOException {

		LinkedHashMap<int[], ArrayList<Integer>> resultMap = new LinkedHashMap<>();
		int len = suffixItems.size();

		if (len <= 1) {
			return resultMap;
		}

		if (len == 2) {
			int itemI = suffixItems.get(0);
			int itemJ = suffixItems.get(1);

			BitSet bitSetIJ = (BitSet) mapItemTS.get(itemI).clone();
			bitSetIJ.and(mapItemTS.get(itemJ));
			intersectionCount.incrementAndGet();

			int[] prefix = new int[prefixKey.length + 2];
			for (int m = 0; m < prefixKey.length; m++) {
				prefix[m] = prefixKey[m];
				bitSetIJ.and(mapItemTS.get(prefix[m]));
				intersectionCount.incrementAndGet();
			}

			ArrayList<int[]> timeIntervals = bitset2intervals(bitSetIJ);
			if (timeIntervals.size() > 0) {
				prefix[prefixKey.length] = itemI;
				prefix[prefixKey.length + 1] = itemJ;
				save(prefix, timeIntervals, prefix.length);
			}
			return resultMap;
		}

		int[] prefix = new int[prefixKey.length + 2];
		BitSet bitSetPrefix = (BitSet) mapItemTS.get(prefixKey[0]).clone();
		prefix[0] = prefixKey[0];
		for (int m = 1; m < prefixKey.length; m++) {
			prefix[m] = prefixKey[m];
			bitSetPrefix.and(mapItemTS.get(prefixKey[m]));
			intersectionCount.incrementAndGet();
		}

		for (int i = 0; i < len - 1; i++) {

			int itemI = suffixItems.get(i);
			BitSet bitSetI = (BitSet) mapItemTS.get(itemI).clone();
			bitSetI.and(bitSetPrefix);
			intersectionCount.incrementAndGet();
			prefix[prefixKey.length] = itemI;

			int[] head = new int[prefixKey.length + 1];
			System.arraycopy(prefix, 0, head, 0, prefixKey.length + 1);

			for (int j = i + 1; j < len; j++) {
				int itemJ = suffixItems.get(j);
				BitSet bitSetIJ = (BitSet) mapItemTS.get(itemJ).clone();
				bitSetIJ.and(bitSetI);
				intersectionCount.incrementAndGet();

				ArrayList<int[]> timeIntervals = bitset2intervals(bitSetIJ);
				if (timeIntervals.size() > 0) {

					prefix[prefixKey.length + 1] = itemJ;

					save(prefix.clone(), timeIntervals, prefix.length);

					if (resultMap.containsKey(head)) {
						resultMap.get(head).add(itemJ);
					} else {
						resultMap.put(head, new ArrayList<Integer>() {
							{
								add(itemJ);
							}
						});
					}
				}
			}
		}

		return resultMap;
	}

	/**
	 * Convert an itemset's timestamps to time-intervals
	 *
	 * @param bitSet the bit vector (timestamps)
	 * @return the list of time intervals
	 */
	private ArrayList<int[]> bitset2intervals(BitSet bitSet) {
		ArrayList<int[]> timeIntervals = new ArrayList<>();
		int left = -1;
		int soPer = maxSoPer;
		int preTS = bitSet.nextSetBit(1);
		int ts = bitSet.nextSetBit(preTS + 1);
		while (ts > 0) {

			if (ts - preTS <= maxPer && left == -1) {
				left = preTS;
				soPer = maxSoPer;
			}

			if (left != -1) {
				soPer = Math.max(0, soPer + ts - preTS - maxPer);
				if (soPer > maxSoPer) {
					if (preTS - left >= minDur) {
						timeIntervals.add(new int[] { left, preTS });
					}
					left = -1;
				}
			}
			preTS = ts;
			ts = bitSet.nextSetBit(preTS + 1);
		}

		// add final time point
		if (left != -1) {
			soPer = Math.max(0, soPer + largestTs - preTS - maxPer);
			if (soPer > maxSoPer) {
				if (preTS - left >= minDur) {
					timeIntervals.add(new int[] { left, preTS });
				}
			} else {
				if (largestTs - left >= minDur) {
					timeIntervals.add(new int[] { left, largestTs });
				}
			}
		}
		return timeIntervals;
	}

	/**
	 * Save an LPP to the memory or an output file - THREAD-SAFE
	 *
	 * @param items         the items of this LPP
	 * @param timeIntervals the periodic time-intervals
	 * @param k             the number of items
	 * @throws IOException if error while writing to the output file
	 */
	private void save(int[] items, ArrayList<int[]> timeIntervals, int k) throws IOException {
		itemsetCount.incrementAndGet();

		if (writer == null) {
			// Synchronized để tránh race condition
			synchronized (patterns) {
				patterns.addItemset(new Itemset(items, timeIntervals), k);
			}
		} else {
			// if the result should be saved to a file
			StringBuilder buffer = new StringBuilder();
			for (int item : items) {
				buffer.append(item);
				buffer.append(" ");
			}
			// Then, write the time-intervals
			buffer.append("#TIME-INTERVALS: ");
			for (int[] timeInterval : timeIntervals) {
				buffer.append("[");
				buffer.append(timeInterval[0]);
				buffer.append(",");
				buffer.append(timeInterval[1]);
				buffer.append("]   ");
			}

			// Thêm vào queue (thread-safe)
			resultQueue.add(buffer.toString());
		}
	}

	/**
	 * Print statistics about the algorithm execution to System.out.
	 */
	public void printStats() {
		System.out.println("=============  LPPM_breadth PARALLEL - STATS =============");
		long temps = endTime - startTimestamp;

		System.out.println(" Threads used: " + numThreads);
		System.out.println(" Total time ~ " + temps + " ms");
		System.out.println(" Itemsets count : " + itemsetCount.get());
		System.out.println(" Maximum memory usage : " + MemoryLogger.getInstance().getMaxMemory() + " mb");
		System.out.println(" Intersection count : " + intersectionCount.get());
		System.out.println("===================================================");
	}

}
