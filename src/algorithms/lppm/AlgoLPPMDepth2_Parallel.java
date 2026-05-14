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
import java.util.List;
import java.util.Map;
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
 * PARALLEL implementation of the LPPM-depth algorithm based on Eclat
 *
 * Parallelization strategy: Song song hóa ở LEVEL 1 (Root level).
 * Mỗi frequent item ở level 1 được xử lý độc lập bởi một thread,
 * thread đó sẽ thực hiện toàn bộ DFS đệ quy cho nhánh của nó.
 *
 * @author Peng yang (original), Parallel version 2026
 */

public class AlgoLPPMDepth2_Parallel {

	/** the maximum periodicity threshold */
	private int maxPer;

	/** the minimum duration threshold */
	private int minDur;

	/** the maximal spillover of periods threshold */
	private int maxSoPer;

	/** number of LPPs found - THREAD-SAFE */
	private AtomicInteger itemsetCount = new AtomicInteger(0);

	/** intersection count - THREAD-SAFE */
	private AtomicLong intersectionCount = new AtomicLong(0);

	/**
	 * The patterns that are found (if the user want to keep them into memory)
	 */
	protected Itemsets patterns = null;

	/** object to write the output file */
	BufferedWriter writer = null;

	/** Queue để lưu kết quả từ các threads - THREAD-SAFE */
	private ConcurrentLinkedQueue<String> resultQueue = new ConcurrentLinkedQueue<>();

	final int BUFFERS_SIZE = 2000;

	/** the largest timestamps of the database */
	private int largestTs;

	/**
	 * if selfIncrement == true --> considering that all transactions in this
	 * database are occurring at a fixed time interval, we have assigned
	 * timestamps for each transaction as increments of 1.
	 */
	private boolean selfIncrement;

	/** start time of the latest execution */
	private long startTimestamp;

	/** end time of the latest execution */
	private long endTime;

	/** Số lượng threads sử dụng */
	private int numThreads;

	/**
	 * Constructor
	 */
	public AlgoLPPMDepth2_Parallel() {
		this.numThreads = Runtime.getRuntime().availableProcessors();
	}

	/**
	 * Constructor với số threads tùy chỉnh
	 */
	public AlgoLPPMDepth2_Parallel(int numThreads) {
		this.numThreads = numThreads;
	}

	/**
	 * Method to run the LPPM-depth algorithm in PARALLEL.
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

		// record the start time
		startTimestamp = System.currentTimeMillis();

		// (1) PREPROCESSING: scan the database to build the convertTimeStamps
		Map<Integer, BitSet> mapItemTS = convertTimeStamps(input);

		// (2) generate periodic frequent pattern of 1-pattern
		ArrayList<Integer> lpp1 = new ArrayList<>();

		Iterator<Map.Entry<Integer, BitSet>> it = mapItemTS.entrySet().iterator();

		while (it.hasNext()) {
			Map.Entry<Integer, BitSet> entry = it.next();
			if (!generatePattern(entry)) {
				it.remove();
			} else {
				lpp1.add(entry.getKey());
			}
		}

		// sort itemsets of size 1 according to lexicographical order.
		Collections.sort(lpp1);

		System.out.println("[PARALLEL] Sử dụng " + numThreads + " threads để xử lý "
			+ lpp1.size() + " frequent items ở level 1");

		// ===== SONG SONG HÓA: Mỗi frequent item ở level 1 = 1 nhánh DFS độc lập =====
		ExecutorService executor = Executors.newFixedThreadPool(numThreads);

		// For each frequent item I according to the total order
		for (int i = 0; i < lpp1.size() - 1; i++) {
			final Integer itemI = lpp1.get(i);
			final BitSet tsSetI = (BitSet) mapItemTS.get(itemI).clone(); // Clone để tránh race condition
			final ArrayList<Integer> lpp1Snapshot = new ArrayList<>(lpp1);
			final Map<Integer, BitSet> mapItemTSSnapshot = new HashMap<>(mapItemTS);
			final int indexI = i;

			// Submit task cho thread pool - mỗi task xử lý 1 nhánh DFS hoàn chỉnh
			executor.submit(() -> {
				try {
					processRootItem(itemI, tsSetI, lpp1Snapshot, mapItemTSSnapshot, indexI);
				} catch (IOException e) {
					e.printStackTrace();
				}
			});
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

		System.out.println("[PARALLEL] Tất cả threads đã hoàn thành");

		// (3) Ghi kết quả từ queue ra file (nếu có)
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

		// Return all frequent itemsets found!
		return patterns;
	}

	/**
	 * Xử lý một root item (được gọi bởi mỗi thread) - thực hiện toàn bộ DFS cho nhánh này
	 *
	 * @param itemI root item
	 * @param tsSetI tidset của itemI
	 * @param lpp1 danh sách tất cả frequent items
	 * @param mapItemTS map item -> tidset
	 * @param indexI index của itemI trong lpp1
	 * @throws IOException
	 */
	private void processRootItem(Integer itemI, BitSet tsSetI, ArrayList<Integer> lpp1,
			Map<Integer, BitSet> mapItemTS, int indexI) throws IOException {

		// Tạo buffer riêng cho thread này
		int[] itemsetBuffer = new int[BUFFERS_SIZE];

		// We create empty equivalence class for storing all 2-itemsets starting with itemI
		List<Integer> equivalenceClassIitems = new ArrayList<>();
		List<BitSet> equivalenceClassItssets = new ArrayList<>();

		itemsetBuffer[0] = itemI;

		// For each item itemJ that is larger than i
		for (int j = indexI + 1; j < lpp1.size(); j++) {
			int itemJ = lpp1.get(j);

			BitSet tsSetJ = mapItemTS.get(itemJ);

			// Calculate the tidset of itemset "IJ"
			BitSet tsSetIJ = (BitSet) tsSetI.clone();
			tsSetIJ.and(tsSetJ);
			intersectionCount.incrementAndGet(); // Thread-safe increment

			ArrayList<int[]> timeIntervals = bitset2intervals(tsSetIJ);
			if (timeIntervals.size() > 0) {
				equivalenceClassIitems.add(itemJ);
				equivalenceClassItssets.add(tsSetIJ);
				save(itemsetBuffer, 1, itemJ, timeIntervals);
			}
		}

		// Process all itemsets from the equivalence class
		if (equivalenceClassIitems.size() > 0) {
			// Gọi đệ quy DFS (tuần tự trong nhánh này)
			processEquivalenceClass(itemsetBuffer, 1, equivalenceClassIitems, equivalenceClassItssets);
		}
	}

	/**
	 * Convert transaction database to vertical database
	 *
	 * @param input the path to a transaction database file
	 * @return the vertical database as a map where key = item and value = bitset of timestamps
	 * @throws IOException if error while reading the file
	 */
	public Map<Integer, BitSet> convertTimeStamps(String input) throws IOException {
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
	 * @return true if it is an LPP, otherwise, false
	 * @throws IOException if error while reading or writing to file
	 */
	private boolean generatePattern(Map.Entry<Integer, BitSet> entry) throws IOException {
		ArrayList<int[]> timeIntervals = new ArrayList<>();
		int left = -1;
		int soPer = maxSoPer;
		BitSet bitSet = entry.getValue();

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
						bitSet.clear(left, preTS);
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
					bitSet.clear(left, preTS);
				}
			} else {
				if (largestTs - left >= minDur) {
					timeIntervals.add(new int[] { left, largestTs });
				} else {
					bitSet.clear(left, largestTs);
				}
			}
		}

		if (timeIntervals.size() > 0) {
			saveSingleItem(entry.getKey(), timeIntervals);
			entry.setValue(bitSet);
			return true;
		}
		return false;
	}

	/**
	 * Process an equivalence class containing several patterns (RECURSIVE - chạy tuần tự trong mỗi nhánh)
	 *
	 * @param prefix                  the prefix of patterns in that class
	 * @param prefixLength            the prefix length (number of items)
	 * @param equivalenceClassItems   the item appended to that prefix for each pattern
	 * @param equivalenceClassItssets a list of bitsets indicating timestamps for each item
	 * @throws IOException if error while writing or reading a file
	 */
	private void processEquivalenceClass(int[] prefix, int prefixLength, List<Integer> equivalenceClassItems,
			List<BitSet> equivalenceClassItssets) throws IOException {

		if (equivalenceClassItems.size() <= 1) {
			return;
		}

		if (equivalenceClassItems.size() == 2) {
			int itemI = equivalenceClassItems.get(0);
			BitSet tsSetI = equivalenceClassItssets.get(0);

			int itemJ = equivalenceClassItems.get(1);
			BitSet tsSetJ = equivalenceClassItssets.get(1);

			BitSet tsSetIJ = (BitSet) tsSetI.clone();
			tsSetIJ.and(tsSetJ);
			intersectionCount.incrementAndGet();

			ArrayList<int[]> timeIntervals = bitset2intervals(tsSetIJ);
			if (timeIntervals.size() > 0) {
				int newPrefixLength = prefixLength + 1;
				prefix[prefixLength] = itemI;
				save(prefix, newPrefixLength, itemJ, timeIntervals);
			}
			return;
		}

		// For each itemset "prefix" + "i"
		for (int i = 0; i < equivalenceClassItems.size() - 1; i++) {
			int itemI = equivalenceClassItems.get(i);
			BitSet tsSetI = equivalenceClassItssets.get(i);

			List<Integer> equivalenceClassISuffixItems = new ArrayList<>();
			List<BitSet> equivalenceClassISuffixtssets = new ArrayList<>();

			int newPrefixLength = prefixLength + 1;
			prefix[prefixLength] = itemI;

			// For each itemset "prefix" + j"
			for (int j = i + 1; j < equivalenceClassItems.size(); j++) {
				int itemJ = equivalenceClassItems.get(j);
				BitSet tsSetJ = equivalenceClassItssets.get(j);

				BitSet tsSetIJ = (BitSet) tsSetI.clone();
				tsSetIJ.and(tsSetJ);
				intersectionCount.incrementAndGet();

				ArrayList<int[]> timeIntervals = bitset2intervals(tsSetIJ);

				if (timeIntervals.size() > 0) {
					equivalenceClassISuffixItems.add(itemJ);
					equivalenceClassISuffixtssets.add(tsSetIJ);
					save(prefix, newPrefixLength, itemJ, timeIntervals);
				}
			}

			if (equivalenceClassISuffixItems.size() > 0) {
				// Recursive call
				processEquivalenceClass(prefix, newPrefixLength, equivalenceClassISuffixItems,
						equivalenceClassISuffixtssets);
			}
		}

		MemoryLogger.getInstance().checkMemory();
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
	 * Save a pattern - THREAD-SAFE
	 *
	 * @param prefix        the prefix of the pattern
	 * @param prefixLen     the prefix length
	 * @param itemJ         the item appended to the prefix
	 * @param timeIntervals the time intervals of that pattern
	 * @throws IOException if error while writing to the output file
	 */
	private void save(int[] prefix, int prefixLen, int itemJ, ArrayList<int[]> timeIntervals) throws IOException {
		itemsetCount.incrementAndGet();

		if (writer != null) {
			StringBuilder buffer = new StringBuilder();
			for (int i = 0; i < prefixLen; i++) {
				int item = prefix[i];
				buffer.append(item);
				buffer.append(" ");
			}
			buffer.append(itemJ);
			buffer.append(" #TIME-INTERVALS: ");
			for (int[] timeInterval : timeIntervals) {
				buffer.append("[");
				buffer.append(timeInterval[0]);
				buffer.append(",");
				buffer.append(timeInterval[1]);
				buffer.append("]   ");
			}

			// Thêm vào queue (thread-safe)
			resultQueue.add(buffer.toString());
		} else {
			int[] itemName = new int[prefixLen + 1];
			System.arraycopy(prefix, 0, itemName, 0, prefixLen);
			itemName[prefixLen] = itemJ;

			// Synchronized để tránh race condition
			synchronized (patterns) {
				patterns.addItemset(new Itemset(itemName, timeIntervals), prefixLen + 1);
			}
		}
	}

	/**
	 * Save a pattern containing a single item - THREAD-SAFE
	 *
	 * @param itemName      the item name
	 * @param timeIntervals the time intervals of that item
	 * @throws IOException if error while writing to the output file
	 */
	private void saveSingleItem(int itemName, ArrayList<int[]> timeIntervals) throws IOException {
		itemsetCount.incrementAndGet();

		if (writer != null) {
			StringBuilder buffer = new StringBuilder();
			buffer.append(itemName);
			buffer.append(" ");
			buffer.append(" #TIME-INTERVALS: ");
			for (int[] timeInterval : timeIntervals) {
				buffer.append("[");
				buffer.append(timeInterval[0]);
				buffer.append(",");
				buffer.append(timeInterval[1]);
				buffer.append("]   ");
			}

			// Thêm vào queue (thread-safe)
			resultQueue.add(buffer.toString());
		} else {
			// Synchronized để tránh race condition
			synchronized (patterns) {
				patterns.addItemset(new Itemset(itemName, timeIntervals), 1);
			}
		}
	}

	/**
	 * Print statistics about the algorithm execution to System.out.
	 */
	public void printStats() {
		System.out.println("=============  LPPM_depth PARALLEL - STATS =============");
		long temps = endTime - startTimestamp;

		System.out.println(" Threads used: " + numThreads);
		System.out.println(" Total time ~ " + temps + " ms");
		System.out.println(" Itemsets count : " + itemsetCount.get());
		System.out.println(" Maximum memory usage : " + MemoryLogger.getInstance().getMaxMemory() + " mb");
		System.out.println(" Intersection count : " + intersectionCount.get());
		System.out.println("===================================================");
	}

}
