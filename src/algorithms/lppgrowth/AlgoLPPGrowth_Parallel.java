package algorithms.lppgrowth;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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

import tools.MemoryLogger;

/*
 * This file is part of the SPMF DATA MINING SOFTWARE
 * (http://www.philippe-fournier-viger.com/spmf).
 * Copyright Peng Yang 2019 — Parallel version 2026 — GPL v3
 */

/**
 * PARALLEL version of LPP-Growth algorithm.
 *
 * ─── Tại sao parallel phức tạp hơn Depth/Breadth? ────────────────────────
 * Trong pftiGrowth(masterTree), mỗi vòng lặp:
 *   1. Save pattern size-k
 *   2. Thu thập prefix paths (READ masterTree)
 *   3. tree.removeTailItem()  ← MUTATE masterTree: dịch timestamps lên parent
 *   4. Dùng timestamps đã cập nhật để tính mapBetaTimeIntervals
 *   5. Build treeBeta (object MỚI, độc lập)
 *   6. Gọi đệ quy pftiGrowth(treeBeta, ...)
 *
 * Bước 3 là sequential dependency: bước k+1 phải dùng tree ĐÃ CẬP NHẬT
 * từ bước k. Không thể parallelize vòng lặp trên masterTree.
 *
 * ─── Chiến lược đúng ─────────────────────────────────────────────────────
 * Phase 1 (TUẦN TỰ): Chạy vòng lặp trên masterTree giống sequential:
 *   - Save size-1 pattern
 *   - Thu thập prefix paths
 *   - removeTailItem()
 *   - Tính mapBetaTimeIntervals
 *   - Build treeBeta
 *
 * Phase 2 (SONG SONG): Với mỗi treeBeta đã build xong:
 *   - Submit pftiGrowth_local(treeBeta, ...) vào thread pool
 *   - treeBeta là object RIÊNG biệt → không share → an toàn
 *
 * Kết quả: pftiGrowth() đệ quy trên các treeBeta độc lập → song song hoàn toàn.
 *
 * @author Peng yang (original sequential), Parallel version 2026
 */
public class AlgoLPPGrowth_Parallel {

	// ─── Timing ──────────────────────────────────────────────────────────────────
	private long startTimestamp;
	private long endTime;

	// ─── DB info ─────────────────────────────────────────────────────────────────
	/** Timestamp lớn nhất — dùng trong getMapBetaTimeIntervals() */
	private int lastTimestamp = -1;

	// ─── Algorithm parameters ────────────────────────────────────────────────────
	private int     maxPer;
	private int     minDur;
	private int     maxSoPer;
	private boolean selfIncrement;
	private int     maxPatternLength = 1000;

	// ─── Thread-safe counters & output ───────────────────────────────────────────
	/**
	 * Đếm số pattern — AtomicInteger để nhiều thread increment an toàn.
	 */
	private final AtomicInteger itemsetCount = new AtomicInteger(0);

	/**
	 * Queue lock-free gom kết quả từ tất cả thread.
	 * ConcurrentLinkedQueue.add() là thread-safe, không cần synchronized.
	 */
	private final ConcurrentLinkedQueue<String> outputQueue = new ConcurrentLinkedQueue<>();

	// ─── Parallel config ─────────────────────────────────────────────────────────
	private final int numThreads;

	static final int BUFFERS_SIZE = 2000;

	/**
	 * Constructor mặc định: dùng tất cả CPU cores có sẵn.
	 */
	public AlgoLPPGrowth_Parallel() {
		this.numThreads = Runtime.getRuntime().availableProcessors();
	}

	/**
	 * Constructor với số threads tùy chỉnh.
	 */
	public AlgoLPPGrowth_Parallel(int numThreads) {
		this.numThreads = Math.max(1, numThreads);
	}

	/**
	 * Chạy thuật toán LPP-Growth song song.
	 * Signature giống hệt AlgoLPPGrowth.runAlgorithm() để dễ thay thế.
	 */
	public Itemsets runAlgorithm(String input, String output,
			int maxPer, int minDur, int maxSoPer,
			boolean selfIncrement) throws IOException {

		// Init
		startTimestamp = System.currentTimeMillis();
		itemsetCount.set(0);
		outputQueue.clear();
		MemoryLogger.getInstance().reset();
		MemoryLogger.getInstance().checkMemory();

		this.maxPer        = maxPer;
		this.minDur        = minDur;
		this.maxSoPer      = maxSoPer;
		this.selfIncrement = selfIncrement;

		// ═══ PHASE 1 (tuần tự): Scan DB + Build masterTree ═══════════════════════
		final Map<Integer, TimeIntervals> mapTimeIntervals =
				scanDatabaseToDetermineTimeIntervalsOfSingleItems(input);

		LPPTree masterTree = new LPPTree();
		buildTreeByScanDataAgain(masterTree, input, mapTimeIntervals);

		if (masterTree.headerList.isEmpty()) {
			writeOutputAndFinish(output);
			return null;
		}

		// ═══ PHASE 2: TUẦN TỰ trên masterTree, SONG SONG trên treeBeta ════════════
		//
		// Vòng lặp tuần tự để sinh các treeBeta — PHẢI tuần tự vì removeTailItem()
		// modify masterTree theo thứ tự. Sau khi có treeBeta, submit vào thread pool.
		//
		ExecutorService executor = Executors.newFixedThreadPool(numThreads);
		List<Future<?>>  futures  = new ArrayList<>();

		// prefix buffer chỉ dùng trong main thread (tuần tự) → an toàn
		int[] mainPrefix = new int[BUFFERS_SIZE];

		while (!masterTree.headerList.isEmpty()) {

			// Lấy tail item (giống sequential)
			Integer item = masterTree.headerList.get(masterTree.headerList.size() - 1);
			mainPrefix[0] = item;

			// Save size-1 pattern (main thread — tuần tự → an toàn)
			saveToQueue(mainPrefix, 1, mapTimeIntervals.get(item).intervals);

			if (maxPatternLength <= 1) {
				// Chỉ cần size-1 → không cần build treeBeta
				masterTree.removeTailItem();
				continue;
			}

			// ── Thu thập prefix paths (READ masterTree — trước removeTailItem) ────
			List<List<LPPNode>>          prefixPaths       = collectPrefixPaths(masterTree, item);
			Map<Integer, List<Integer>>  mapTimestampsBeta = collectTimestamps(prefixPaths);

			// ── removeTailItem() — TUẦN TỰ: chuyển timestamps lên parent ──────────
			masterTree.removeTailItem();

			// ── Tính mapBetaTimeIntervals (dùng timestamps đã cập nhật) ───────────
			Map<Integer, TimeIntervals> mapBetaIntervals = getMapBetaTimeIntervals(mapTimestampsBeta);

			if (mapBetaIntervals.isEmpty()) continue;

			// ── Build treeBeta (object MỚI, hoàn toàn độc lập) ───────────────────
			LPPTree treeBeta = new LPPTree();
			for (List<LPPNode> pp : prefixPaths) {
				treeBeta.addPrefixPath(pp, mapBetaIntervals);
			}
			if (treeBeta.root.childs.isEmpty()) continue;
			treeBeta.createHeaderList(masterTree.headerList, mapBetaIntervals);

			// ── SUBMIT TASK: pftiGrowth trên treeBeta → chạy trong thread riêng ──
			// Capture final refs để dùng trong lambda
			final LPPTree                taskTree      = treeBeta;
			final Map<Integer, TimeIntervals> taskIntervals = mapBetaIntervals;
			final int                    rootItem      = item;

			futures.add(executor.submit(() -> {
				try {
					// LOCAL buffer cho thread này — không share với ai
					int[] localPrefix = new int[BUFFERS_SIZE];
					localPrefix[0] = rootItem;
					// Đệ quy mining trên conditional tree (hoàn toàn độc lập)
					pftiGrowth_local(taskTree, localPrefix, 1, taskIntervals);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				return null;
			}));
		}

		// ═══ Đợi tất cả thread xong ══════════════════════════════════════════════
		for (Future<?> f : futures) {
			try { f.get(); }
			catch (Exception e) { throw new RuntimeException("Thread failed: " + e.getMessage(), e); }
		}
		executor.shutdown();
		try { executor.awaitTermination(60, TimeUnit.MINUTES); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); }

		MemoryLogger.getInstance().checkMemory();
		endTime = System.currentTimeMillis();

		// ═══ Ghi kết quả ra file (single-thread sau khi tất cả thread xong) ═════
		writeOutputAndFinish(output);
		return null;
	}

	// ─── pftiGrowth_local: chạy tuần tự trong mỗi thread ────────────────────────
	/**
	 * Logic giống hệt pftiGrowth() gốc nhưng:
	 *   - tree là private object của thread này → removeTailItem() an toàn.
	 *   - prefix là local array → không share.
	 *   - saveToQueue() thay vì saveItemset() → thread-safe.
	 *   - KHÔNG gọi executor.submit() thêm → tránh task explosion.
	 */
	@SuppressWarnings("serial")
	private void pftiGrowth_local(LPPTree tree, int[] prefix, int prefixLength,
			Map<Integer, TimeIntervals> mapTimeIntervals) throws IOException {

		if (prefixLength == maxPatternLength) return;

		while (!tree.headerList.isEmpty()) {
			Integer item = tree.headerList.get(tree.headerList.size() - 1);
			prefix[prefixLength] = item;

			// Save pattern (thread-safe via outputQueue)
			saveToQueue(prefix, prefixLength + 1, mapTimeIntervals.get(item).intervals);

			if (prefixLength + 1 < maxPatternLength) {

				// Thu thập prefix paths
				List<List<LPPNode>>         prefixPaths       = collectPrefixPaths(tree, item);
				Map<Integer, List<Integer>> mapTimestampsBeta = collectTimestamps(prefixPaths);

				// removeTailItem() — an toàn vì tree là private của thread này
				tree.removeTailItem();

				Map<Integer, TimeIntervals> mapBetaIntervals = getMapBetaTimeIntervals(mapTimestampsBeta);

				if (!mapBetaIntervals.isEmpty()) {
					LPPTree treeBeta = new LPPTree();
					for (List<LPPNode> pp : prefixPaths) {
						treeBeta.addPrefixPath(pp, mapBetaIntervals);
					}
					if (!treeBeta.root.childs.isEmpty()) {
						treeBeta.createHeaderList(tree.headerList, mapBetaIntervals);
						// Đệ quy tuần tự trong thread này (không submit thêm task)
						pftiGrowth_local(treeBeta, prefix, prefixLength + 1, mapBetaIntervals);
					}
				}
			} else {
				tree.removeTailItem();
			}
		}

		MemoryLogger.getInstance().checkMemory();
	}

	// ─── collectPrefixPaths: đọc tree, không sửa ─────────────────────────────────
	/**
	 * Thu thập danh sách prefix paths của item từ tree (chỉ ĐỌC, không sửa).
	 * Dùng cả trong main thread (đọc masterTree tuần tự)
	 * và trong pftiGrowth_local (đọc conditional tree của thread).
	 */
	private List<List<LPPNode>> collectPrefixPaths(LPPTree tree, Integer item) {
		List<List<LPPNode>> prefixPaths = new ArrayList<>();
		LPPNode path = tree.mapItemNodes.get(item);

		while (path != null) {
			if (path.parent.itemID != -1) {
				List<LPPNode> prefixPath = new ArrayList<>();
				prefixPath.add(path); // node đầu giữ timestamps
				LPPNode parent = path.parent;
				while (parent.itemID != -1) {
					prefixPath.add(parent);
					parent = parent.parent;
				}
				prefixPaths.add(prefixPath);
			}
			path = path.nodeLink;
		}
		return prefixPaths;
	}

	// ─── collectTimestamps: gom timestamps từ prefix paths ───────────────────────
	/**
	 * Gom timestamps của các ancestor items từ prefix paths.
	 * Pure computation — an toàn gọi từ nhiều thread (chỉ đọc LPPNode.timestamps).
	 * LPPNode.timestamps là read-only tại thời điểm gọi (removeTailItem chưa chạy).
	 */
	private Map<Integer, List<Integer>> collectTimestamps(List<List<LPPNode>> prefixPaths) {
		Map<Integer, List<Integer>> map = new HashMap<>();
		for (List<LPPNode> prefixPath : prefixPaths) {
			List<Integer> pathTimestamps = prefixPath.get(0).timestamps;
			for (int k = 1; k < prefixPath.size(); k++) {
				int ancestorItem = prefixPath.get(k).itemID;
				map.computeIfAbsent(ancestorItem, x -> new ArrayList<>())
				   .addAll(pathTimestamps);
			}
		}
		return map;
	}

	// ─── saveToQueue: thread-safe output ─────────────────────────────────────────
	/**
	 * Ghi pattern vào outputQueue — thread-safe.
	 * Sort items trước khi ghi (giống sequential saveItemset).
	 */
	private void saveToQueue(int[] itemset, int length, List<int[]> timeIntervals) {
		itemsetCount.incrementAndGet(); // AtomicInteger — thread-safe

		// Tạo copy đã sort (local array, không share)
		int[] sorted = Arrays.copyOf(itemset, length);
		Arrays.sort(sorted);

		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < length; i++) {
			sb.append(sorted[i]);
			if (i < length - 1) sb.append(' ');
		}
		sb.append(" #TIME-INTERVALS: ");
		for (int[] ti : timeIntervals) {
			sb.append('[').append(ti[0]).append(',').append(ti[1]).append("]   ");
		}
		outputQueue.add(sb.toString()); // ConcurrentLinkedQueue — lock-free
	}

	// ─── writeOutputAndFinish: drain queue → file ────────────────────────────────
	private void writeOutputAndFinish(String outputPath) throws IOException {
		if (outputPath != null) {
			try (BufferedWriter bw = new BufferedWriter(new FileWriter(outputPath))) {
				for (String line : outputQueue) {
					bw.write(line);
					bw.newLine();
				}
			}
		}
	}

	// ─── getMapBetaTimeIntervals: copy nguyên từ sequential ──────────────────────
	/**
	 * Pure computation — an toàn gọi từ nhiều thread.
	 * Mỗi call có local mapBetaTimeIntervals và soPer riêng.
	 */
	private Map<Integer, TimeIntervals> getMapBetaTimeIntervals(
			Map<Integer, List<Integer>> mapTimestampsBeta) {

		Map<Integer, TimeIntervals> mapBetaTimeIntervals = new HashMap<>();
		Map<Integer, Integer>       soPer                = new HashMap<>();

		for (Map.Entry<Integer, List<Integer>> entry : mapTimestampsBeta.entrySet()) {
			TimeIntervals  timeIntervals = new TimeIntervals();
			List<Integer>  timestamps    = entry.getValue();
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
						soPer.put(entry.getKey(),
								Math.max(0, soPer.get(entry.getKey()) + (per - maxPer)));
						if (soPer.get(entry.getKey()) > maxSoPer) {
							if (preTS - timeIntervals.left >= minDur)
								timeIntervals.addTimeInterval(preTS);
							timeIntervals.left = -1;
						}
					}
				}
				preTS = timestamp;
			}

			if (timeIntervals.left != -1) {
				soPer.put(entry.getKey(), Math.max(0,
						soPer.get(entry.getKey()) + (lastTimestamp - preTS - maxPer)));
				if (soPer.get(entry.getKey()) <= maxSoPer
						&& lastTimestamp - timeIntervals.left >= minDur)
					timeIntervals.addTimeInterval(lastTimestamp);
				if (soPer.get(entry.getKey()) > maxSoPer
						&& preTS - timeIntervals.left >= minDur)
					timeIntervals.addTimeInterval(preTS);
			}

			if (!timeIntervals.intervals.isEmpty())
				mapBetaTimeIntervals.put(entry.getKey(), timeIntervals);
		}
		mapTimestampsBeta.clear();
		return mapBetaTimeIntervals;
	}

	// ─── scanDatabaseToDetermineTimeIntervalsOfSingleItems: copy từ sequential ───
	private Map<Integer, TimeIntervals> scanDatabaseToDetermineTimeIntervalsOfSingleItems(
			String input) throws IOException {

		BufferedReader reader = new BufferedReader(new FileReader(input));
		String line;

		Map<Integer, TimeIntervals> mapTimeIntervals = new HashMap<>();
		Map<Integer, Integer>       preTimestamp     = new HashMap<>();
		Map<Integer, Integer>       soPer            = new HashMap<>();

		if (selfIncrement) {
			int ts = 1;
			while ((line = reader.readLine()) != null) {
				if (line.isEmpty() || line.charAt(0) == '#'
						|| line.charAt(0) == '%' || line.charAt(0) == '@') continue;
				for (String tok : line.split(" ")) {
					Integer item = Integer.parseInt(tok);
					if (mapTimeIntervals.containsKey(item)) {
						int pre_ts = preTimestamp.get(item);
						int per    = ts - pre_ts;
						if (per == 0) { preTimestamp.put(item, ts); continue; }
						TimeIntervals ti = mapTimeIntervals.get(item);
						if (per <= maxPer && ti.left == -1) { ti.left = pre_ts; soPer.put(item, maxSoPer); }
						if (ti.left != -1) {
							soPer.put(item, Math.max(0, soPer.get(item) + (per - maxPer)));
							if (soPer.get(item) > maxSoPer) {
								if (pre_ts - ti.left >= minDur) ti.addTimeInterval(pre_ts);
								ti.left = -1;
							}
						}
					} else {
						mapTimeIntervals.put(item, new TimeIntervals());
					}
					preTimestamp.put(item, ts);
				}
				ts++;
			}
			lastTimestamp = ts - 1;
		} else {
			int ts = -1;
			while ((line = reader.readLine()) != null) {
				if (line.isEmpty() || line.charAt(0) == '#'
						|| line.charAt(0) == '%' || line.charAt(0) == '@') continue;
				String[] parts = line.split("\\|");
				String[] items = parts[0].split(" ");
				ts = Integer.parseInt(parts[1]);
				for (String tok : items) {
					Integer item = Integer.parseInt(tok);
					if (preTimestamp.containsKey(item)) {
						int preTS = preTimestamp.get(item);
						int per   = ts - preTS;
						TimeIntervals ti = mapTimeIntervals.get(item);
						if (per <= maxPer && ti.left == -1) { ti.left = preTS; soPer.put(item, maxSoPer); }
						if (ti.left != -1) {
							soPer.put(item, Math.max(0, soPer.get(item) + (per - maxPer)));
							if (soPer.get(item) > maxSoPer) {
								if (preTS - ti.left >= minDur) ti.addTimeInterval(preTS);
								ti.left = -1;
							}
						}
					} else {
						mapTimeIntervals.put(item, new TimeIntervals());
					}
					preTimestamp.put(item, ts);
				}
			}
			lastTimestamp = ts;
		}
		reader.close();

		Iterator<Map.Entry<Integer, TimeIntervals>> it = mapTimeIntervals.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, TimeIntervals> entry = it.next();
			if (entry.getValue().left != -1) {
				soPer.put(entry.getKey(), Math.max(0, soPer.get(entry.getKey())
						+ (lastTimestamp - preTimestamp.get(entry.getKey()) - maxPer)));
				if (soPer.get(entry.getKey()) <= maxSoPer
						&& lastTimestamp - entry.getValue().left >= minDur)
					entry.getValue().addTimeInterval(lastTimestamp);
				if (soPer.get(entry.getKey()) > maxSoPer
						&& preTimestamp.get(entry.getKey()) - entry.getValue().left >= minDur)
					entry.getValue().addTimeInterval(preTimestamp.get(entry.getKey()));
				entry.getValue().left = -1;
			}
			if (entry.getValue().intervals.isEmpty()) it.remove();
		}
		return mapTimeIntervals;
	}

	// ─── buildTreeByScanDataAgain: copy từ sequential ────────────────────────────
	private void buildTreeByScanDataAgain(LPPTree tree, String input,
			Map<Integer, TimeIntervals> mapTI) throws IOException {

		BufferedReader reader = new BufferedReader(new FileReader(input));
		String line;

		if (selfIncrement) {
			int ts = 1;
			while ((line = reader.readLine()) != null) {
				if (line.isEmpty() || line.charAt(0) == '#'
						|| line.charAt(0) == '%' || line.charAt(0) == '@') continue;
				List<Integer> txn = new ArrayList<>();
				for (String tok : line.trim().split(" ")) {
					Integer item = Integer.parseInt(tok);
					if (mapTI.containsKey(item) && mapTI.get(item).isInside(ts)
							&& !txn.contains(item)) txn.add(item);
				}
				sortByDuration(txn, mapTI);
				tree.addTransaction(txn, ts);
				ts++;
			}
		} else {
			int ts = -1;
			while ((line = reader.readLine()) != null) {
				if (line.isEmpty() || line.charAt(0) == '#'
						|| line.charAt(0) == '%' || line.charAt(0) == '@') continue;
				String[] parts = line.trim().split("\\|");
				String[] items = parts[0].trim().split(" ");
				ts = Integer.parseInt(parts[1]);
				List<Integer> txn = new ArrayList<>();
				for (String tok : items) {
					Integer item = Integer.parseInt(tok);
					if (mapTI.containsKey(item) && mapTI.get(item).isInside(ts)
							&& !txn.contains(item)) txn.add(item);
				}
				sortByDuration(txn, mapTI);
				if (!txn.isEmpty()) tree.addTransaction(txn, ts);
			}
		}
		reader.close();
		tree.createHeaderList(null, mapTI);
	}

	private void sortByDuration(List<Integer> txn, Map<Integer, TimeIntervals> mapTI) {
		txn.sort((a, b) -> {
			int cmp = mapTI.get(b).getTotalDuration() - mapTI.get(a).getTotalDuration();
			return cmp != 0 ? cmp : Integer.compare(a, b);
		});
	}

	// ─── Stats ───────────────────────────────────────────────────────────────────
	public void printStats() {
		System.out.println("=======  LPP-Growth PARALLEL - STATS =======");
		System.out.println(" Threads       : " + numThreads);
		System.out.println(" Patterns found: " + itemsetCount.get());
		System.out.println(" Total time    : " + (endTime - startTimestamp) + " ms");
		System.out.println(" Max memory    : "
				+ MemoryLogger.getInstance().getMaxMemory() + " mb");
		System.out.println("=============================================");
	}

	public void setMaximumPatternLength(int length) { this.maxPatternLength = length; }
	public void cancelSelfIncrement()               { this.selfIncrement    = false;  }
}
