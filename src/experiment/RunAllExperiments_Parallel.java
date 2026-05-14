package experiment;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Master Runner Class for PARALLEL Experiments
 *
 * Chạy tất cả các thử nghiệm parallel và so sánh với bản sequential.
 * Test với nhiều số lượng threads khác nhau để tìm cấu hình tối ưu.
 */
public class RunAllExperiments_Parallel {

    public static void main(String[] args) throws IOException {
        // Prepare Physical Output Folders
        new java.io.File(ExperimentLPPGrowth_Parallel.OUT_DIR).mkdirs();

        // 1. Definition List of Test Subjects
        String[][] datasets = {
            { "retail",  "retail.txt"      }
            // Bỏ kosarak vì có thể gây OOM, test riêng nếu cần
            // { "kosarak", "kosarak.dat.txt"  }
        };

        // 2. Control Experiment Hyperparameters
        int[] maxPerValues = { 10, 20, 30 };
        int   minDur       = 50;
        int   maxSoPer     = 3;

        // 3. Test với các số lượng threads khác nhau
        int[] threadCounts = {
            1,  // Baseline (giống sequential nhưng dùng parallel code)
            2,
            4,
            Runtime.getRuntime().availableProcessors()  // Tất cả processors
        };

        System.out.println("========================================================");
        System.out.println("     PARALLEL LOCALLY PERIODIC PATTERN MINING");
        System.out.println("========================================================");
        System.out.println("Available processors: " + Runtime.getRuntime().availableProcessors());

        List<ExperimentResult> allResults = new ArrayList<>();

        for (String[] ds : datasets) {
            String dsName = ds[0];
            String dsPath = ds[1];

            System.out.println("\n## DATASET: " + dsName.toUpperCase());

            for (int maxPer : maxPerValues) {
                System.out.println("\n  -> Params: maxPer=" + maxPer + "  minDur=" + minDur + "  maxSoPer=" + maxSoPer);

                for (int numThreads : threadCounts) {
                    System.out.println("\n  ==== Testing with " + numThreads + " thread(s) ====");

                    // EXPERIMENT ONE: LPP-Growth PARALLEL
                    ExperimentLPPGrowth_Parallel expGr = new ExperimentLPPGrowth_Parallel(
                        dsName, dsPath, maxPer, minDur, maxSoPer, true, numThreads
                    );
                    allResults.add(expGr.run());
                    System.gc(); // Force memory sweep

                    // EXPERIMENT TWO: LPPM-Breadth PARALLEL
                    ExperimentLPPMBreadth_Parallel expBr = new ExperimentLPPMBreadth_Parallel(
                        dsName, dsPath, maxPer, minDur, maxSoPer, true, numThreads
                    );
                    allResults.add(expBr.run());
                    System.gc(); // Force memory sweep

                    // EXPERIMENT THREE: LPPM-Depth PARALLEL
                    ExperimentLPPMDepth_Parallel expDp = new ExperimentLPPMDepth_Parallel(
                        dsName, dsPath, maxPer, minDur, maxSoPer, true, numThreads
                    );
                    allResults.add(expDp.run());
                    System.gc(); // Force memory sweep
                }
            }
        }

        // Export unified benchmarks globally into a CSV matrix structure
        String csvPath = ExperimentLPPGrowth_Parallel.OUT_DIR + "summary_parallel_experiments.csv";
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(csvPath))) {
            bw.write("Algorithm,Dataset,maxPer,minDur,maxSoPer,Runtime_ms,Memory_MB,Patterns\n");
            for (ExperimentResult r : allResults) {
                bw.write(r.toCsvRow() + "\n");
            }
        }

        System.out.println("\n========================================================");
        System.out.println("  ALL PARALLEL EXPERIMENTS COMPLETED SUCCESSFULLY!");
        System.out.println("  CSV Summary: " + csvPath);
        System.out.println("========================================================");

        // Print speedup analysis
        printSpeedupAnalysis(allResults);
    }

    /**
     * Phân tích speedup so với baseline (1 thread)
     */
    private static void printSpeedupAnalysis(List<ExperimentResult> results) {
        System.out.println("\n========================================================");
        System.out.println("  SPEEDUP ANALYSIS (vs 1 thread baseline)");
        System.out.println("========================================================");

        // Group results by algorithm and dataset
        for (ExperimentResult r : results) {
            if (r.runtimeMs == -1) continue; // Skip OOM results

            // Tìm baseline (1 thread) tương ứng
            String baseAlgoName = r.algorithmName.replaceAll("-\\d+T$", "-1T");
            ExperimentResult baseline = results.stream()
                .filter(b -> b.algorithmName.equals(baseAlgoName) &&
                            b.datasetName.equals(r.datasetName) &&
                            b.maxPer == r.maxPer)
                .findFirst()
                .orElse(null);

            if (baseline != null && baseline.runtimeMs > 0 && !r.algorithmName.equals(baseAlgoName)) {
                double speedup = (double) baseline.runtimeMs / r.runtimeMs;
                System.out.printf("%s on %s (maxPer=%d): %.2fx speedup (%d ms -> %d ms)\n",
                    r.algorithmName, r.datasetName, r.maxPer, speedup, baseline.runtimeMs, r.runtimeMs);
            }
        }

        System.out.println("========================================================");
    }
}
