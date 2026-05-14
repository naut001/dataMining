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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import tools.MemoryLogger;

/*
 * Parallel version of LPPM-depth algorithm.
 *
 * Chiến lược song song hóa:
 *   - Phase 1 (tuần tự): convertTimeStamps() + generatePattern()
 *     vì generatePattern() modify BitSet của mapItemTS → phải tuần tự.
 *   - Phase 2 (song song): outer loop "for i in lpp1"
 *     Mỗi i hoàn toàn độc lập → submit mỗi i làm 1 task.
 *     Mỗi task tạo local prefix array riêng (không share itemsetBuffer).
 *     mapItemTS chỉ đọc (clone BitSet trước .and()).
 *   - Output: ConcurrentLinkedQueue<String> gom kết quả từ các thread.
 *     Sau khi tất cả thread xong → ghi ra file 1 lần (single-thread).
 *
 * @author Peng yang (original sequential), Parallel version 2026
 */
public class AlgoLPPMDepth_Parallel {

	// ─── Algorithm parameters (set once, read-only after runAlgorithm starts) ───
	private int  maxPer;
	private int  minDur;
	private int  maxSoPer;
	private int  largestTs;
	private boolean selfIncrement;

	// ─── Thread-safe counters ────────────────────────────────────────────────────
	/** Đếm số pattern tìm được — AtomicInteger để nhiều thread cùng ghi an toàn */
	private final AtomicInteger itemsetCount     = new AtomicInteger(0);
	/** Đếm số phép AND BitSet — AtomicLong để nhiều thread cùng ghi an toàn */
	private final AtomicLong    intersectionCount = new AtomicLong(0);

	// ─── Thread-safe output queue ────────────────────────────────────────────────
	/**
	 * Hàng đợi lock-free để gom kết quả từ tất cả thread.
	 * ConcurrentLinkedQueue.add() là thread-safe, không cần synchronized.
	 * Sau khi tất cả thread xong, main thread drain queue → ghi ra file 1 lần.
	 */
	private final ConcurrentLinkedQueue<String> outputQueue = new ConcurrentLinkedQueue<>();

	// ─── Parallel config ─────────────────────────────────────────────────────────
	private final int numThreads;

	// ─── Timing ──────────────────────────────────────────────────────────────────
	private long startTimestamp;
	private long endTime;

	static final int BUFFERS_SIZE = 2000;

	/**
	 * Constructor mặc định: dùng tất cả CPU cores có sẵn.
	 */
	public AlgoLPPMDepth_Parallel() {
		this.numThreads = Runtime.getRuntime().availableProcessors();
	}

	/**
	 * Constructor với số threads tùy chỉnh.
	 */
	public AlgoLPPMDepth_Parallel(int numThreads) {
		this.numThreads = Math.max(1, numThreads);
	}

	/**
	 * Chạy thuật toán LPPM-depth song song.
	 * Signature giống hệt AlgoLPPMDepth2.runAlgorithm() để dễ thay thế.
	 */
	public Itemsets runAlgorithm(String input, String output,
			int maxPer, int minDur, int maxSoPer,
			boolean selfIncrement) throws IOException {

		// Init
		MemoryLogger.getInstance().reset();
		this.maxPer        = maxPer;
		this.minDur        = minDur;
		this.maxSoPer      = maxSoPer;
		this.selfIncrement = selfIncrement;
		itemsetCount.set(0);
		intersectionCount.set(0);
		outputQueue.clear();
		startTimestamp = System.currentTimeMillis();

		// ═══ PHASE 1 (tuần tự): Build vertical BitSet database ═══════════════════
		// Phải tuần tự vì đọc file.
		Map<Integer, BitSet> mapItemTS = convertTimeStamps(input);

		// ═══ PHASE 2 (tuần tự): Generate size-1 patterns ════════════════════════
		// Phải tuần tự vì generatePattern() gọi bitSet.clear() → modify mapItemTS.
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
		Collections.sort(lpp1);

		// ═══ PHASE 3 (song song): Outer loop over lpp1 ═══════════════════════════
		// Mỗi i trong lpp1 là 1 task hoàn toàn độc lập:
		//   - Đọc mapItemTS (read-only, chỉ clone BitSet trước .and())
		//   - Tạo local prefix array → không share itemsetBuffer
		//   - Kết quả gom vào outputQueue (ConcurrentLinkedQueue)
		ExecutorService executor = Executors.newFixedThreadPool(numThreads);
		List<Future<?>> futures  = new ArrayList<>();

		for (int i = 0; i < lpp1.size() - 1; i++) {
			final int     finalI = i;
			final Integer itemI  = lpp1.get(i);
			// Lấy reference đến BitSet của itemI — read-only trong task
			final BitSet  tsSetI = mapItemTS.get(itemI);
			// lpp1 là final-effectively, có thể capture trong lambda

			// ═ SUBMIT TASK: mỗi i = 1 task độc lập ═════════════════════════════
			futures.add(executor.submit(() -> {
				try {
					// LOCAL prefix buffer — không share với thread khác
					int[] localPrefix = new int[BUFFERS_SIZE];
					localPrefix[0] = itemI;

					List<Integer> equivalenceClassIitems  = new ArrayList<>();
					List<BitSet>  equivalenceClassItssets = new ArrayList<>();

					for (int j = finalI + 1; j < lpp1.size(); j++) {
						int    itemJ  = lpp1.get(j);
						BitSet tsSetJ = mapItemTS.get(itemJ); // read-only reference

						// PHẢI clone trước .and() — không mutate BitSet chung
						BitSet tsSetIJ = (BitSet) tsSetI.clone();
						tsSetIJ.and(tsSetJ);
						intersectionCount.incrementAndGet(); // AtomicLong

						ArrayList<int[]> timeIntervals = bitset2intervals(tsSetIJ);
						if (!timeIntervals.isEmpty()) {
							equivalenceClassIitems.add(itemJ);
							equivalenceClassItssets.add(tsSetIJ);
							// Ghi kết quả vào queue (thread-safe)
							saveToQueue(localPrefix, 1, itemJ, timeIntervals);
						}
					}

					if (!equivalenceClassIitems.isEmpty()) {
						// Đệ quy DFS tuần tự trong thread này
						// KHÔNG submit thêm task (tránh task explosion)
						processEquivalenceClass(localPrefix, 1,
								equivalenceClassIitems, equivalenceClassItssets);
					}
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				return null;
			}));
		}

		// ═══ Đợi tất cả thread xong ══════════════════════════════════════════════
		for (Future<?> f : futures) {
			try {
				f.get();
			} catch (Exception e) {
				throw new RuntimeException("Thread failed: " + e.getMessage(), e);
			}
		}
		executor.shutdown();
		try {
			executor.awaitTermination(60, TimeUnit.MINUTES);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		MemoryLogger.getInstance().checkMemory();
		endTime = System.currentTimeMillis();

		// ═══ Ghi kết quả ra file (single-thread sau khi tất cả thread xong) ═════
		if (output != null) {
			try (BufferedWriter bw = new BufferedWriter(new FileWriter(output))) {
				for (String line : outputQueue) {
					bw.write(line);
					bw.newLine();
				}
			}
		}

		return null; // Parallel version ghi ra file, không trả về in-memory
	}

	// ─── processEquivalenceClass: chạy tuần tự trong mỗi thread ─────────────────
	/**
	 * Logic giống hệt AlgoLPPMDepth2.processEquivalenceClass() nhưng:
	 *   - Dùng prefix array đã được truyền vào (local của thread, không share).
	 *   - Gọi saveToQueue() thay vì save() → ghi vào outputQueue.
	 *   - intersectionCount.incrementAndGet() thay vì intersectionCount++.
	 *   - KHÔNG gọi executor.submit() thêm (tránh task explosion).
	 */
	private void processEquivalenceClass(int[] prefix, int prefixLength,
			List<Integer> equivalenceClassItems,
			List<BitSet>  equivalenceClassItssets) throws IOException {

		if (equivalenceClassItems.size() <= 1) return;

		if (equivalenceClassItems.size() == 2) {
			int    itemI   = equivalenceClassItems.get(0);
			BitSet tsSetI  = equivalenceClassItssets.get(0);
			int    itemJ   = equivalenceClassItems.get(1);
			BitSet tsSetJ  = equivalenceClassItssets.get(1);

			BitSet tsSetIJ = (BitSet) tsSetI.clone();
			tsSetIJ.and(tsSetJ);
			intersectionCount.incrementAndGet();

			ArrayList<int[]> timeIntervals = bitset2intervals(tsSetIJ);
			if (!timeIntervals.isEmpty()) {
				int newPrefixLength  = prefixLength + 1;
				prefix[prefixLength] = itemI;
				saveToQueue(prefix, newPrefixLength, itemJ, timeIntervals);
			}
			return;
		}

		for (int i = 0; i < equivalenceClassItems.size() - 1; i++) {
			int    itemI  = equivalenceClassItems.get(i);
			BitSet tsSetI = equivalenceClassItssets.get(i);

			List<Integer> suffixItems  = new ArrayList<>();
			List<BitSet>  suffixTssets = new ArrayList<>();

			int newPrefixLength  = prefixLength + 1;
			prefix[prefixLength] = itemI;

			for (int j = i + 1; j < equivalenceClassItems.size(); j++) {
				int    itemJ  = equivalenceClassItems.get(j);
				BitSet tsSetJ = equivalenceClassItssets.get(j);

				BitSet tsSetIJ = (BitSet) tsSetI.clone();
				tsSetIJ.and(tsSetJ);
				intersectionCount.incrementAndGet();

				ArrayList<int[]> timeIntervals = bitset2intervals(tsSetIJ);
				if (!timeIntervals.isEmpty()) {
					suffixItems.add(itemJ);
					suffixTssets.add(tsSetIJ);
					saveToQueue(prefix, newPrefixLength, itemJ, timeIntervals);
				}
			}

			if (!suffixItems.isEmpty()) {
				// Đệ quy tuần tự trong thread này
				processEquivalenceClass(prefix, newPrefixLength, suffixItems, suffixTssets);
			}
		}
		MemoryLogger.getInstance().checkMemory();
	}

	// ─── convertTimeStamps: copy nguyên từ sequential ────────────────────────────
	/**
	 * Scan DB → build vertical BitSet database.
	 * Chạy tuần tự (đọc file).
	 */
	private Map<Integer, BitSet> convertTimeStamps(String input) throws IOException {
		BufferedReader reader    = new BufferedReader(new FileReader(input));
		String         line;
		Map<Integer, BitSet> mapItemTS = new HashMap<>();

		if (selfIncrement) {
			int ts = 1;
			while ((line = reader.readLine()) != null) {
				if (line.isEmpty() || line.charAt(0) == '#'
						|| line.charAt(0) == '%' || line.charAt(0) == '@') continue;
				for (String tok : line.split(" ")) {
					int item = Integer.parseInt(tok);
					if (!mapItemTS.containsKey(item)) mapItemTS.put(item, new BitSet());
					mapItemTS.get(item).set(ts);
				}
				ts++;
			}
			largestTs = ts - 1;
		} else {
			int ts = 0;
			while ((line = reader.readLine()) != null) {
				if (line.isEmpty() || line.charAt(0) == '#'
						|| line.charAt(0) == '%' || line.charAt(0) == '@') continue;
				String[] parts     = line.split("\\|");
				String[] lineItems = parts[0].split(" ");
				ts = Integer.parseInt(parts[1]);
				for (String tok : lineItems) {
					int item = Integer.parseInt(tok);
					if (!mapItemTS.containsKey(item)) mapItemTS.put(item, new BitSet());
					mapItemTS.get(item).set(ts);
				}
			}
			largestTs = ts;
		}
		reader.close();
		return mapItemTS;
	}

	// ─── generatePattern: copy nguyên từ sequential ──────────────────────────────
	/**
	 * Kiểm tra và ghi nhận size-1 pattern.
	 * Gọi bitSet.clear() → modify BitSet trong mapItemTS → phải chạy tuần tự.
	 */
	private boolean generatePattern(Map.Entry<Integer, BitSet> entry) throws IOException {
		ArrayList<int[]> timeIntervals = new ArrayList<>();
		int    left   = -1;
		int    soPer  = maxSoPer;
		BitSet bitSet = entry.getValue();

		int preTS = bitSet.nextSetBit(1);
		int ts    = bitSet.nextSetBit(preTS + 1);
		while (ts > 0) {
			if (ts - preTS <= maxPer && left == -1) {
				left  = preTS;
				soPer = maxSoPer;
			}
			if (left != -1) {
				soPer = Math.max(0, soPer + ts - preTS - maxPer);
				if (soPer > maxSoPer) {
					if (preTS - left >= minDur) timeIntervals.add(new int[]{left, preTS});
					else                        bitSet.clear(left, preTS);
					left = -1;
				}
			} else {
				bitSet.clear(preTS);
			}
			preTS = ts;
			ts    = bitSet.nextSetBit(preTS + 1);
		}

		if (left != -1) {
			soPer = Math.max(0, soPer + largestTs - preTS - maxPer);
			if (soPer > maxSoPer) {
				if (preTS - left >= minDur) timeIntervals.add(new int[]{left, preTS});
				else                        bitSet.clear(left, preTS);
			} else {
				if (largestTs - left >= minDur) timeIntervals.add(new int[]{left, largestTs});
				else                            bitSet.clear(left, largestTs);
			}
		}

		if (!timeIntervals.isEmpty()) {
			// Ghi size-1 pattern — chạy trong main thread (tuần tự) → an toàn
			saveSingleItemToQueue(entry.getKey(), timeIntervals);
			entry.setValue(bitSet);
			return true;
		}
		return false;
	}

	// ─── bitset2intervals: copy nguyên từ sequential ─────────────────────────────
	/**
	 * Pure computation — an toàn khi gọi từ nhiều thread (không dùng shared state).
	 */
	private ArrayList<int[]> bitset2intervals(BitSet bitSet) {
		ArrayList<int[]> timeIntervals = new ArrayList<>();
		int left  = -1;
		int soPer = maxSoPer;
		int preTS = bitSet.nextSetBit(1);
		int ts    = bitSet.nextSetBit(preTS + 1);

		while (ts > 0) {
			if (ts - preTS <= maxPer && left == -1) {
				left  = preTS;
				soPer = maxSoPer;
			}
			if (left != -1) {
				soPer = Math.max(0, soPer + ts - preTS - maxPer);
				if (soPer > maxSoPer) {
					if (preTS - left >= minDur) timeIntervals.add(new int[]{left, preTS});
					left = -1;
				}
			}
			preTS = ts;
			ts    = bitSet.nextSetBit(preTS + 1);
		}

		if (left != -1) {
			soPer = Math.max(0, soPer + largestTs - preTS - maxPer);
			if (soPer > maxSoPer) {
				if (preTS - left    >= minDur) timeIntervals.add(new int[]{left, preTS});
			} else {
				if (largestTs - left >= minDur) timeIntervals.add(new int[]{left, largestTs});
			}
		}
		return timeIntervals;
	}

	// ─── Thread-safe output methods ───────────────────────────────────────────────

	/**
	 * Ghi pattern (multi-item) vào outputQueue — thread-safe.
	 * Format: "item1 item2 ... itemN itemJ #TIME-INTERVALS: [l,r]   ..."
	 */
	private void saveToQueue(int[] prefix, int prefixLen, int itemJ,
			ArrayList<int[]> timeIntervals) {
		itemsetCount.incrementAndGet(); // AtomicInteger — thread-safe
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < prefixLen; i++) {
			sb.append(prefix[i]).append(' ');
		}
		sb.append(itemJ);
		sb.append(" #TIME-INTERVALS: ");
		for (int[] ti : timeIntervals) {
			sb.append('[').append(ti[0]).append(',').append(ti[1]).append("]   ");
		}
		outputQueue.add(sb.toString()); // ConcurrentLinkedQueue.add() — lock-free
	}

	/**
	 * Ghi size-1 pattern vào outputQueue.
	 * Format: "item  #TIME-INTERVALS: [l,r]   ..."
	 */
	private void saveSingleItemToQueue(int itemName, ArrayList<int[]> timeIntervals) {
		itemsetCount.incrementAndGet();
		StringBuilder sb = new StringBuilder();
		sb.append(itemName).append("  #TIME-INTERVALS: ");
		for (int[] ti : timeIntervals) {
			sb.append('[').append(ti[0]).append(',').append(ti[1]).append("]   ");
		}
		outputQueue.add(sb.toString());
	}

	// ─── Stats ───────────────────────────────────────────────────────────────────

	public void printStats() {
		System.out.println("=======  LPPM-Depth PARALLEL - STATS =======");
		System.out.println(" Threads           : " + numThreads);
		System.out.println(" Total time        : " + (endTime - startTimestamp) + " ms");
		System.out.println(" Itemsets count    : " + itemsetCount.get());
		System.out.println(" Max memory usage  : "
				+ MemoryLogger.getInstance().getMaxMemory() + " mb");
		System.out.println(" Intersection count: " + intersectionCount.get());
		System.out.println("=============================================");
	}
}
