package experiment;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import algorithms.lppm.AlgoLPPMBreadth2_Parallel;
import tools.MemoryLogger;

/**
 * Experiment Class: LPPM-Breadth Algorithm PARALLEL VERSION
 *
 * This wrapper runs the LPPM-Breadth PARALLEL solution.
 * Uses ExecutorService with level-wise synchronization.
 * At each level k, prefix groups are processed in parallel.
 *
 * Performance characteristic: Should be faster than sequential version on multi-core CPUs,
 * but may still face memory issues on large datasets due to BitSet overhead.
 */
public class ExperimentLPPMBreadth_Parallel {

    public static final String DATA_DIR = ExperimentLPPGrowth_Parallel.DATA_DIR;
    public static final String OUT_DIR  = ExperimentLPPGrowth_Parallel.OUT_DIR;

    private final String datasetName;
    private final String inputPath;
    private final int maxPer;
    private final int minDur;
    private final int maxSoPer;
    private final boolean selfIncrement;
    private final int numThreads;

    /**
     * Constructor với số threads tùy chỉnh
     */
    public ExperimentLPPMBreadth_Parallel(String datasetName, String inputFile,
                                int maxPer, int minDur, int maxSoPer,
                                boolean selfIncrement, int numThreads) {
        this.datasetName   = datasetName;
        this.inputPath     = DATA_DIR + inputFile;
        this.maxPer        = maxPer;
        this.minDur        = minDur;
        this.maxSoPer      = maxSoPer;
        this.selfIncrement = selfIncrement;
        this.numThreads    = numThreads;
    }

    /**
     * Constructor mặc định - sử dụng số processors có sẵn
     */
    public ExperimentLPPMBreadth_Parallel(String datasetName, String inputFile,
                                int maxPer, int minDur, int maxSoPer,
                                boolean selfIncrement) {
        this(datasetName, inputFile, maxPer, minDur, maxSoPer, selfIncrement,
             Runtime.getRuntime().availableProcessors());
    }

    public ExperimentResult run() throws IOException {
        String label = datasetName + "__maxPer" + maxPer + "__minDur" + minDur + "__maxSoPer" + maxSoPer;
        String outputFile = OUT_DIR + "LPPMBreadth_Parallel__" + label + "__output.txt";
        String statsFile  = OUT_DIR + "LPPMBreadth_Parallel__" + label + "__stats.txt";

        printHeader("LPPM-Breadth PARALLEL", label, outputFile);

        MemoryLogger.getInstance().reset();
        long startTime = System.currentTimeMillis();

        try {
            // Instantiate and run the PARALLEL version
            AlgoLPPMBreadth2_Parallel algo = new AlgoLPPMBreadth2_Parallel(numThreads);
            algo.runAlgorithm(inputPath, outputFile, maxPer, minDur, maxSoPer, selfIncrement);

            long   runtime      = System.currentTimeMillis() - startTime;
            double maxMemoryMB  = MemoryLogger.getInstance().getMaxMemory();
            long   patternCount = countLines(outputFile);

            algo.printStats();
            System.out.printf("  [Wrapper] Runtime (measured outside): %d ms\n", runtime);
            System.out.printf("  [Wrapper] Patterns found: %d\n", patternCount);

            ExperimentResult result = new ExperimentResult(
                "LPPM-Breadth-Parallel-" + numThreads + "T", datasetName, maxPer, minDur, maxSoPer,
                runtime, maxMemoryMB, patternCount
            );
            ExperimentLPPGrowth_Parallel.writeStats(statsFile, result);
            System.out.println("  OK: Stats -> " + statsFile);
            return result;

        } catch (OutOfMemoryError oom) {
            // Safely intercept OOM errors so our Master Runner doesn't crash.
            System.err.println("\n  WARNING: OutOfMemoryError! Dataset '" + datasetName + "' is too large for LPPM-Breadth!");
            System.err.println("  Reason: BitSet vertical DB requires massive RAM traversing the BFS candidates.");
            System.err.println("  Writing OOM results to stats file...");

            ExperimentResult oomResult = new ExperimentResult(
                "LPPM-Breadth-Parallel-" + numThreads + "T", datasetName, maxPer, minDur, maxSoPer,
                -1L, -1.0, -1L
            );
            writeOomStats(statsFile, oomResult);
            System.out.println("  OK: OOM Stats -> " + statsFile);
            return oomResult;
        }
    }

    private void writeOomStats(String statsFile, ExperimentResult r) throws IOException {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(statsFile))) {
            bw.write("=== LPPM-Breadth PARALLEL - Experiment Stats ===\n");
            bw.write("Dataset         : " + r.datasetName + "\n");
            bw.write("maxPer          : " + r.maxPer + "\n");
            bw.write("minDur          : " + r.minDur + "\n");
            bw.write("maxSoPer        : " + r.maxSoPer + "\n");
            bw.write("-------------------------------------\n");
            bw.write("STATUS          : OUT OF MEMORY (OOM)\n");
            bw.write("Runtime (ms)    : N/A (OOM)\n");
            bw.write("Max Memory (MB) : > heap limit\n");
            bw.write("Patterns found  : N/A (OOM)\n");
            bw.write("Note: LPPM-Breadth PARALLEL ran out of memory allocating massive Vertical BitSets.\n");
        }
    }

    private long countLines(String filePath) {
        try {
            return Files.lines(Paths.get(filePath)).count();
        } catch (IOException e) {
            return -1L;
        }
    }

    private void printHeader(String algoName, String label, String outputFile) {
        System.out.println("\n========================================================");
        System.out.printf( "  EXPERIMENT: %s (%d threads)\n", algoName, numThreads);
        System.out.println("========================================================");
        System.out.println("  Dataset : " + datasetName + " (" + inputPath + ")");
        System.out.println("  Params  : maxPer=" + maxPer + " minDur=" + minDur + " maxSoPer=" + maxSoPer);
        System.out.println("  Threads : " + numThreads);
        System.out.println("  Output  : " + outputFile);
    }

    /**
     * Main method để test riêng thuật toán này
     */
    public static void main(String[] args) throws IOException {
        // Test với retail dataset
        ExperimentLPPMBreadth_Parallel exp = new ExperimentLPPMBreadth_Parallel(
            "retail", "retail.txt", 10, 50, 3, true
        );

        // Tạo output directory
        new java.io.File(OUT_DIR).mkdirs();

        exp.run();
    }
}
