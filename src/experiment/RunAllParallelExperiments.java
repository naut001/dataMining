package experiment;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Master runner cho tất cả parallel experiments.
 *
 * - Chạy sequential version để lấy baseline
 * - Chạy parallel version và verify kết quả
 * - In speedup ratio
 * - Export CSV so sánh sequential vs parallel
 */
public class RunAllParallelExperiments {

    public static void main(String[] args) throws IOException {

        // Tạo output directories
        new java.io.File(ExperimentLPPGrowth_Parallel.OUT_DIR).mkdirs();
        new java.io.File(ExperimentLPPGrowth.OUT_DIR).mkdirs();

        // Số threads = số CPU cores có sẵn
        int numThreads = Runtime.getRuntime().availableProcessors();
        System.out.println("========================================================");
        System.out.println("      PARALLEL LPPM EXPERIMENTS");
        System.out.println("========================================================");
        System.out.println("Available processors: " + numThreads + " threads");

        String[][] datasets = {
            { "retail",  "retail.txt"     },
            { "kosarak", "kosarak.dat.txt" }
        };

        int[] maxPerValues = { 10, 20, 30 };
        int   minDur       = 50;
        int   maxSoPer     = 3;

        List<ExperimentResult> seqResults = new ArrayList<>();
        List<ExperimentResult> parResults = new ArrayList<>();

        for (String[] ds : datasets) {
            String dsName = ds[0];
            String dsPath = ds[1];

            System.out.println("\n## DATASET: " + dsName.toUpperCase());

            for (int maxPer : maxPerValues) {
                System.out.printf("%n  -> maxPer=%d  minDur=%d  maxSoPer=%d%n",
                                   maxPer, minDur, maxSoPer);

                // ── LPP-GROWTH ──────────────────────────────────────────────────
                ExperimentResult rSeqGr = new ExperimentLPPGrowth(
                    dsName, dsPath, maxPer, minDur, maxSoPer, true).run();
                seqResults.add(rSeqGr);
                System.gc();

                ExperimentResult rParGr = new ExperimentLPPGrowth_Parallel(
                    dsName, dsPath, maxPer, minDur, maxSoPer, true, numThreads).run();
                parResults.add(rParGr);

                printSpeedup("LPP-Growth", rSeqGr, rParGr, numThreads);
                verifyResults("LPP-Growth", dsName, maxPer, minDur, maxSoPer, rSeqGr, rParGr);
                System.gc();

                // ── LPPM-BREADTH ─────────────────────────────────────────────────
                ExperimentResult rSeqBr = new ExperimentLPPMBreadth(
                    dsName, dsPath, maxPer, minDur, maxSoPer, true).run();
                seqResults.add(rSeqBr);
                System.gc();

                ExperimentResult rParBr = new ExperimentLPPMBreadth_Parallel(
                    dsName, dsPath, maxPer, minDur, maxSoPer, true, numThreads).run();
                parResults.add(rParBr);

                printSpeedup("LPPM-Breadth", rSeqBr, rParBr, numThreads);
                verifyResults("LPPM-Breadth", dsName, maxPer, minDur, maxSoPer, rSeqBr, rParBr);
                System.gc();

                // ── LPPM-DEPTH ────────────────────────────────────────────────────
                ExperimentResult rSeqDp = new ExperimentLPPMDepth(
                    dsName, dsPath, maxPer, minDur, maxSoPer, true).run();
                seqResults.add(rSeqDp);
                System.gc();

                ExperimentResult rParDp = new ExperimentLPPMDepth_Parallel(
                    dsName, dsPath, maxPer, minDur, maxSoPer, true, numThreads).run();
                parResults.add(rParDp);

                printSpeedup("LPPM-Depth", rSeqDp, rParDp, numThreads);
                verifyResults("LPPM-Depth", dsName, maxPer, minDur, maxSoPer, rSeqDp, rParDp);
                System.gc();
            }
        }

        // Export speedup CSV
        String csvPath = ExperimentLPPGrowth_Parallel.OUT_DIR + "speedup_summary.csv";
        exportSpeedupCsv(seqResults, parResults, csvPath, numThreads);

        System.out.println("\n========================================================");
        System.out.println("  ALL PARALLEL EXPERIMENTS COMPLETED!");
        System.out.println("  Speedup CSV: " + csvPath);
        System.out.println("========================================================");
    }

    // ─── In speedup ratio ────────────────────────────────────────────────────────
    private static void printSpeedup(String algo,
            ExperimentResult seq, ExperimentResult par, int numThreads) {
        if (seq.runtimeMs > 0 && par.runtimeMs > 0) {
            double speedup    = (double) seq.runtimeMs / par.runtimeMs;
            double efficiency = speedup / numThreads * 100.0;
            System.out.printf("  ⚡ [%s] Speedup: %.2fx (efficiency %.1f%%) | seq=%dms par=%dms%n",
                               algo, speedup, efficiency, seq.runtimeMs, par.runtimeMs);
        } else {
            System.out.printf("  ⚡ [%s] Speedup: N/A (OOM in one version)%n", algo);
        }
    }

    // ─── Verify pattern count ────────────────────────────────────────────────────
    private static void verifyResults(String algo, String dsName,
            int maxPer, int minDur, int maxSoPer,
            ExperimentResult seq, ExperimentResult par) {

        if (seq.patternCount < 0 || par.patternCount < 0) {
            System.out.printf("  ⚠️  [%s/%s] One side OOM — cannot verify%n", algo, dsName);
            return;
        }
        if (seq.patternCount == par.patternCount) {
            System.out.printf("  ✅ [%s/%s maxPer=%d] Pattern count OK: %d%n",
                               algo, dsName, maxPer, seq.patternCount);
        } else {
            System.err.printf("  ❌ [%s/%s maxPer=%d] MISMATCH: seq=%d par=%d%n",
                               algo, dsName, maxPer, seq.patternCount, par.patternCount);
        }
    }

    // ─── Export CSV ──────────────────────────────────────────────────────────────
    private static void exportSpeedupCsv(List<ExperimentResult> seqList,
            List<ExperimentResult> parList, String csvPath, int numThreads) throws IOException {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(csvPath))) {
            bw.write("Algorithm,Dataset,maxPer,minDur,maxSoPer,"
                   + "Seq_ms,Par_ms,Speedup,Efficiency_pct,Threads,Match\n");
            int n = Math.min(seqList.size(), parList.size());
            for (int i = 0; i < n; i++) {
                ExperimentResult s = seqList.get(i);
                ExperimentResult p = parList.get(i);
                double speedup = (s.runtimeMs > 0 && p.runtimeMs > 0)
                               ? (double) s.runtimeMs / p.runtimeMs : -1.0;
                double eff     = speedup > 0 ? speedup / numThreads * 100.0 : -1.0;
                String match   = (s.patternCount >= 0 && s.patternCount == p.patternCount)
                               ? "YES" : "NO";
                bw.write(String.format(java.util.Locale.US,
                    "%s,%s,%d,%d,%d,%d,%d,%.2f,%.1f,%d,%s%n",
                    s.algorithmName, s.datasetName,
                    s.maxPer, s.minDur, s.maxSoPer,
                    s.runtimeMs, p.runtimeMs, speedup, eff, numThreads, match));
            }
        }
        System.out.println("  CSV written: " + csvPath);
    }
}
