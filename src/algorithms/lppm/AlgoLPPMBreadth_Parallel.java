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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import tools.MemoryLogger;

/*
 * Parallel version of LPPM-breadth algorithm (SPM strategy).
 *
 * Chiến lược song song hóa:
 *   - Phase 1 (tuần tự): convertTimeStamps() + generatePattern()
 *     vì generatePattern() modify BitSet → phải tuần tự.
 *   - generateCandidate2 (song song): mỗi item i là 1 task độc lập.
 *   - generateCandidateK (song song với level barrier):
 *       Tại mỗi level k, các entries của combinationMap hoàn toàn độc lập.
 *       Dùng executor.invokeAll() = submit all + wait all = automatic barrier.
 *       Level K+1 chỉ bắt đầu sau khi tất cả tasks ở level K hoàn thành.
 *   - BitSet luôn được clone() trước .and() → không mutate shared state.
 *   - Output: ConcurrentLinkedQueue<String> gom từ các thread.
 *     Sau khi tất cả thread xong → ghi ra file 1 lần.
 *
 * @author Peng yang (original sequential), Parallel version 2026
 */
public class AlgoLPPMBreadth_Parallel {

	// ─── Algorithm parameters ────────────────────────────────────────────────────
	private int  maxPer;
	private int  minDur;
	private int  maxSoPer;
	private int  largestTs;
	private boolean selfIncrement;

	// ─── Thread-safe counters ────────────────────────────────────────────────────
	/** Đếm số pattern tìm được */
	private final AtomicInteger itemsetCount      = new AtomicInteger(0);
	/** Đếm số phép AND BitSet */
	private final AtomicLong    intersectionCount = new AtomicLong(0);

	// ─── Thread-safe output queue ────────────────────────────────────────────────
	/**
	 * Queue lock-free gom kết quả từ tất cả thread.
	 * ConcurrentLinkedQueue.add() là thread-safe.
	 */
	private final ConcurrentLinkedQueue<String> outputQueue = new ConcurrentLinkedQueue<>();

	// ─── Parallel config ─────────────────────────────────────────────────────────
	private final int numThreads;
	private ExecutorService executor;

	// ─── Timing ──────────────────────────────────────────────────────────────────
	private long startTimestamp;
	private long endTime;

	/**
	 * Constructor mặc định: dùng tất cả CPU cores có sẵn.
	 */
	public AlgoLPPMBreadth_Parallel() {
		this.numThreads = Runtime.getRuntime().availableProcessors();
	}

	/**
	 * Constructor với số threads tùy chỉnh.
	 */
	public AlgoLPPMBreadth_Parallel(int numThreads) {
		this.numThreads = Math.max(1, numThreads);
	}

	/**
	 * Chạy thuật toán LPPM-breadth song song.
	 * Signature giống hệt AlgoLPPMBreadth2.runAlgorithm() để dễ thay thế.
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
		Map<Integer, BitSet> mapItemTS = convertTimeStamps(input);

		// ═══ PHASE 2 (tuần tự): Generate size-1 patterns ════════════════════════
		// Phải tuần tự vì generatePattern() gọi bitSet.clear() → modify mapItemTS.
		ArrayList<Integer> lpp1 = new ArrayList<>();
		Iterator<Map.Entry<Integer, BitSet>> it = mapItemTS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, BitSet> entry = it.next();
			if (!generatePattern(entry, 1)) it.remove();
			else                            lpp1.add(entry.getKey());
		}
		Collections.sort(lpp1);

		// ═══ PHASE 3 (song song): generateCandidate2 + level-wise loop ══════════
		executor = Executors.newFixedThreadPool(numThreads);

		// generateCandidate2 song song (mỗi i = 1 task)
		LinkedHashMap<int[], ArrayList<Integer>> combinationMap =
				generateCandidate2Parallel(lpp1, mapItemTS);

		// Level-wise loop: mỗi level dùng invokeAll() để đảm bảo barrier
		while (!combinationMap.isEmpty()) {
			combinationMap = generateCandidateKParallel(combinationMap, mapItemTS);
		}

		// Shutdown executor
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

		return null;
	}

	// ─── generateCandidate2Parallel ──────────────────────────────────────────────
	/**
	 * Song song hóa generateCandidate2: mỗi item i là 1 task độc lập.
	 * Merge kết quả theo thứ tự i để giữ determinism của LinkedHashMap.
	 */
	private LinkedHashMap<int[], ArrayList<Integer>> generateCandidate2Parallel(
			ArrayList<Integer> lpp1,
			Map<Integer, BitSet> mapItemTS) throws IOException {

		// Mỗi task trả về entry (head→suffixes) hoặc null nếu không có candidate nào
		List<Future<Map.Entry<int[], ArrayList<Integer>>>> futures = new ArrayList<>();

		for (int i = 0; i < lpp1.size() - 1; i++) {
			final int    fi    = i;
			final int    itemI = lpp1.get(fi);
			final BitSet bsI   = mapItemTS.get(itemI); // read-only reference

			// ═ TASK: xử lý 1 item i ═════════════════════════════════════════════
			futures.add(executor.submit(() -> {
				int[]             head     = new int[]{itemI};
				ArrayList<Integer> suffixes = new ArrayList<>();

				for (int j = fi + 1; j < lpp1.size(); j++) {
					int    itemJ  = lpp1.get(j);
					// PHẢI clone trước .and() — không mutate BitSet chung
					BitSet bsIJ   = (BitSet) mapItemTS.get(itemJ).clone();
					bsIJ.and(bsI);
					intersectionCount.incrementAndGet();

					ArrayList<int[]> ti = bitset2intervals(bsIJ);
					if (!ti.isEmpty()) {
						saveToQueue(new int[]{itemI, itemJ}, ti, 2); // enqueue
						suffixes.add(itemJ);
					}
				}
				return suffixes.isEmpty() ? null
						: Map.entry(head, suffixes);
			}));
		}

		// Merge kết quả theo thứ tự i (giữ thứ tự LinkedHashMap giống sequential)
		LinkedHashMap<int[], ArrayList<Integer>> combinationMap = new LinkedHashMap<>();
		for (Future<Map.Entry<int[], ArrayList<Integer>>> f : futures) {
			try {
				Map.Entry<int[], ArrayList<Integer>> e = f.get();
				if (e != null) combinationMap.put(e.getKey(), e.getValue());
			} catch (Exception ex) {
				throw new RuntimeException(ex);
			}
		}

		MemoryLogger.getInstance().checkMemory();
		return combinationMap;
	}

	// ─── generateCandidateKParallel ──────────────────────────────────────────────
	/**
	 * Song song hóa generateCandidateK với level barrier.
	 *
	 * Mỗi entry trong combinationMap = 1 Callable độc lập.
	 * invokeAll() = submit all + wait all = automatic barrier đồng bộ.
	 * Level K+1 chỉ bắt đầu sau khi tất cả Callable ở level K trả về.
	 * Merge kết quả single-thread theo thứ tự entries → giữ determinism.
	 */
	private LinkedHashMap<int[], ArrayList<Integer>> generateCandidateKParallel(
			LinkedHashMap<int[], ArrayList<Integer>> combinationMap,
			Map<Integer, BitSet> mapItemTS) throws IOException {

		// Chuyển entrySet sang List để giữ thứ tự khi merge kết quả
		List<Map.Entry<int[], ArrayList<Integer>>> entries =
				new ArrayList<>(combinationMap.entrySet());

		// Danh sách Callable — mỗi entry = 1 task
		List<Callable<List<Map.Entry<int[], ArrayList<Integer>>>>> tasks = new ArrayList<>();

		for (Map.Entry<int[], ArrayList<Integer>> entry : entries) {
			final int[]             entryKey   = entry.getKey();
			final ArrayList<Integer> entryValue = entry.getValue();

			tasks.add(() -> {
				// Kết quả cục bộ của task này (các candidates mới từ entry này)
				List<Map.Entry<int[], ArrayList<Integer>>> localResult = new ArrayList<>();
				processOneEntry(entryKey, entryValue, mapItemTS, localResult);
				return localResult;
			});
		}

		// ═══ BARRIER: invokeAll() đợi TẤT CẢ tasks hoàn thành ═══════════════════
		List<Future<List<Map.Entry<int[], ArrayList<Integer>>>>> futures;
		try {
			futures = executor.invokeAll(tasks);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new LinkedHashMap<>();
		}

		// Merge kết quả single-thread theo thứ tự entries (giữ determinism)
		LinkedHashMap<int[], ArrayList<Integer>> newCombinationMap = new LinkedHashMap<>();
		for (Future<List<Map.Entry<int[], ArrayList<Integer>>>> f : futures) {
			try {
				List<Map.Entry<int[], ArrayList<Integer>>> partialResult = f.get();
				for (Map.Entry<int[], ArrayList<Integer>> e : partialResult) {
					newCombinationMap.put(e.getKey(), e.getValue());
				}
			} catch (Exception ex) {
				throw new RuntimeException(ex);
			}
		}

		MemoryLogger.getInstance().checkMemory();
		return newCombinationMap;
	}

	// ─── processOneEntry: xử lý 1 entry của combinationMap (chạy trong thread) ──
	/**
	 * Logic giống hệt 1 iteration của generateCandidateK sequential, nhưng:
	 *   - Kết quả (newCombinationMap entries) trả về qua localResult (không share).
	 *   - saveToQueue() thay vì save().
	 *   - intersectionCount.incrementAndGet() thay vì intersectionCount++.
	 *   - BitSet luôn clone() trước .and().
	 */
	@SuppressWarnings("serial")
	private void processOneEntry(int[] entryKey, ArrayList<Integer> entryValue,
			Map<Integer, BitSet> mapItemTS,
			List<Map.Entry<int[], ArrayList<Integer>>> localResult) throws IOException {

		int len = entryValue.size();
		if (len <= 1) return;

		if (len == 2) {
			int    itemI  = entryValue.get(0);
			int    itemJ  = entryValue.get(1);
			BitSet bsIJ   = (BitSet) mapItemTS.get(itemI).clone();
			bsIJ.and(mapItemTS.get(itemJ));
			intersectionCount.incrementAndGet();

			int[] prefix = new int[entryKey.length + 2];
			for (int m = 0; m < entryKey.length; m++) {
				prefix[m] = entryKey[m];
				bsIJ.and(mapItemTS.get(prefix[m]));
				intersectionCount.incrementAndGet();
			}

			ArrayList<int[]> ti = bitset2intervals(bsIJ);
			if (!ti.isEmpty()) {
				prefix[entryKey.length]     = itemI;
				prefix[entryKey.length + 1] = itemJ;
				saveToQueue(prefix, ti, prefix.length);
			}
			return; // len==2 không tạo next-level candidates
		}

		// len > 2: general case
		int[] prefix = new int[entryKey.length + 2];

		// Tính bitSetPrefix = AND của tất cả items trong prefix key
		BitSet bsPrefix = (BitSet) mapItemTS.get(entryKey[0]).clone();
		prefix[0] = entryKey[0];
		for (int m = 1; m < entryKey.length; m++) {
			prefix[m] = entryKey[m];
			bsPrefix.and(mapItemTS.get(entryKey[m]));
			intersectionCount.incrementAndGet();
		}

		for (int i = 0; i < len - 1; i++) {
			int    itemI = entryValue.get(i);
			BitSet bsI   = (BitSet) mapItemTS.get(itemI).clone();
			bsI.and(bsPrefix);
			intersectionCount.incrementAndGet();
			prefix[entryKey.length] = itemI;

			// head = prefix[0..entryKey.length] inclusive
			int[] head = new int[entryKey.length + 1];
			System.arraycopy(prefix, 0, head, 0, entryKey.length + 1);

			ArrayList<Integer> localSuffixes = new ArrayList<>();

			for (int j = i + 1; j < len; j++) {
				int    itemJ = entryValue.get(j);
				BitSet bsIJ  = (BitSet) mapItemTS.get(itemJ).clone();
				bsIJ.and(bsI);
				intersectionCount.incrementAndGet();

				ArrayList<int[]> ti = bitset2intervals(bsIJ);
				if (!ti.isEmpty()) {
					prefix[entryKey.length + 1] = itemJ;
					// PHẢI clone prefix trước khi save — prefix bị reuse trong vòng lặp j
					saveToQueue(prefix.clone(), ti, prefix.length);
					localSuffixes.add(itemJ);
				}
			}

			if (!localSuffixes.isEmpty()) {
				// Thêm vào kết quả cục bộ (không share với thread khác)
				final ArrayList<Integer> finalSuffixes = localSuffixes;
				localResult.add(Map.entry(head, finalSuffixes));
			}
		}
	}

	// ─── convertTimeStamps: copy nguyên từ sequential ────────────────────────────
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
	private boolean generatePattern(Map.Entry<Integer, BitSet> entry, int k) throws IOException {
		ArrayList<int[]> timeIntervals = new ArrayList<>();
		int    left   = -1;
		int    soPer  = maxSoPer;
		BitSet bitSet = entry.getValue();

		int preTS = bitSet.nextSetBit(1);
		int ts    = bitSet.nextSetBit(preTS + 1);
		while (ts > 0) {
			if (ts - preTS <= maxPer && left == -1) { left = preTS; soPer = maxSoPer; }
			if (left != -1) {
				soPer = Math.max(0, soPer + ts - preTS - maxPer);
				if (soPer > maxSoPer) {
					if (preTS - left >= minDur) timeIntervals.add(new int[]{left, preTS});
					else                        bitSet.clear(left, preTS + 1);
					left = -1;
				}
			} else { bitSet.clear(preTS); }
			preTS = ts;
			ts    = bitSet.nextSetBit(preTS + 1);
		}
		if (left != -1) {
			soPer = Math.max(0, soPer + largestTs - preTS - maxPer);
			if (soPer > maxSoPer) {
				if (preTS - left    >= minDur) timeIntervals.add(new int[]{left, preTS});
				else                           bitSet.clear(left, preTS + 1);
			} else {
				if (largestTs - left >= minDur) timeIntervals.add(new int[]{left, largestTs});
				else                            bitSet.clear(left, largestTs + 1);
			}
		}
		if (!timeIntervals.isEmpty()) {
			saveToQueue(new int[]{entry.getKey()}, timeIntervals, k); // main thread → safe
			entry.setValue(bitSet);
			return true;
		}
		return false;
	}

	// ─── bitset2intervals: copy nguyên từ sequential ─────────────────────────────
	/** Pure computation — an toàn khi gọi từ nhiều thread. */
	private ArrayList<int[]> bitset2intervals(BitSet bitSet) {
		ArrayList<int[]> timeIntervals = new ArrayList<>();
		int left  = -1;
		int soPer = maxSoPer;
		int preTS = bitSet.nextSetBit(1);
		int ts    = bitSet.nextSetBit(preTS + 1);
		while (ts > 0) {
			if (ts - preTS <= maxPer && left == -1) { left = preTS; soPer = maxSoPer; }
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

	// ─── Thread-safe output ───────────────────────────────────────────────────────
	/**
	 * Ghi pattern vào outputQueue — thread-safe.
	 * Format: "item1 item2 ... #TIME-INTERVALS: [l,r]   ..."
	 */
	private void saveToQueue(int[] items, ArrayList<int[]> timeIntervals, int k) {
		itemsetCount.incrementAndGet(); // AtomicInteger
		StringBuilder sb = new StringBuilder();
		for (int item : items) sb.append(item).append(' ');
		sb.append("#TIME-INTERVALS: ");
		for (int[] ti : timeIntervals) {
			sb.append('[').append(ti[0]).append(',').append(ti[1]).append("]   ");
		}
		outputQueue.add(sb.toString()); // lock-free
	}

	// ─── Stats ───────────────────────────────────────────────────────────────────
	public void printStats() {
		System.out.println("=======  LPPM-Breadth PARALLEL - STATS =======");
		System.out.println(" Threads           : " + numThreads);
		System.out.println(" Total time        : " + (endTime - startTimestamp) + " ms");
		System.out.println(" Itemsets count    : " + itemsetCount.get());
		System.out.println(" Max memory usage  : "
				+ MemoryLogger.getInstance().getMaxMemory() + " mb");
		System.out.println(" Intersection count: " + intersectionCount.get());
		System.out.println("===============================================");
	}
}
