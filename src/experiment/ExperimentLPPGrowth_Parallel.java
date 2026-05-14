package experiment;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import algorithms.lppgrowth.AlgoLPPGrowth_Parallel;
import tools.MemoryLogger;

/**
 * Experiment Class: LPP-Growth Algorithm PARALLEL VERSION
 *
 * This class serves as a benchmark wrapper for the parallel LPP-Growth algorithm.
 * Uses ExecutorService to parallelize the Header Table Loop.
 *
 * Performance characteristic: Should be faster than sequential version on multi-core CPUs.
 */
public class ExperimentLPPGrowth_Parallel {

    public static final String DATA_DIR = "src/data/";
    public static final String OUT_DIR  = "outputs_parallel/";

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
    public ExperimentLPPGrowth_Parallel(String datasetName, String inputFile,
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
    public ExperimentLPPGrowth_Parallel(String datasetName, String inputFile,
                                int maxPer, int minDur, int maxSoPer,
                                boolean selfIncrement) {
        this(datasetName, inputFile, maxPer, minDur, maxSoPer, selfIncrement,
             Runtime.getRuntime().availableProcessors());
    }

    /**
     * Executes the LPP-Growth PARALLEL algorithm and monitors System Resources.
     */
    public ExperimentResult run() throws IOException {
        String label = datasetName + "__maxPer" + maxPer + "__minDur" + minDur + "__maxSoPer" + maxSoPer;
        String outputFile = OUT_DIR + "LPPGrowth_Parallel__" + label + "__output.txt";
        String statsFile  = OUT_DIR + "LPPGrowth_Parallel__" + label + "__stats.txt";

        printHeader("LPP-Growth PARALLEL", label, outputFile);

        MemoryLogger.getInstance().reset();
        long startTime = System.currentTimeMillis();

        // Instantiate and run the PARALLEL version
        AlgoLPPGrowth_Parallel algo = new AlgoLPPGrowth_Parallel(numThreads);
        algo.runAlgorithm(inputPath, outputFile, maxPer, minDur, maxSoPer, selfIncrement);

        long   runtime      = System.currentTimeMillis() - startTime;
        double maxMemoryMB  = MemoryLogger.getInstance().getMaxMemory();
        long   patternCount = countLines(outputFile);

        algo.printStats();
        System.out.printf("  [Wrapper] Runtime (measured outside): %d ms\n", runtime);
        System.out.printf("  [Wrapper] Patterns found: %d\n", patternCount);

        // Package results to return to the Master Runner
        ExperimentResult result = new ExperimentResult(
            "LPP-Growth-Parallel-" + numThreads + "T", datasetName, maxPer, minDur, maxSoPer,
            runtime, maxMemoryMB, patternCount
        );
        writeStats(statsFile, result);
        System.out.println("  OK: Stats -> " + statsFile);

        return result;
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

    public static void writeStats(String statsFile, ExperimentResult r) throws IOException {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(statsFile))) {
            bw.write("=== " + r.algorithmName + " - Experiment Stats ===\n");
            bw.write("Dataset         : " + r.datasetName + "\n");
            bw.write("maxPer          : " + r.maxPer + "\n");
            bw.write("minDur          : " + r.minDur + "\n");
            bw.write("maxSoPer        : " + r.maxSoPer + "\n");
            bw.write("-------------------------------------\n");
            bw.write("Runtime (ms)    : " + r.runtimeMs + "\n");
            bw.write("Max Memory (MB) : " + r.maxMemoryMB + "\n");
            bw.write("Patterns found  : " + r.patternCount + "\n");
        }
    }

    /**
     * Main method để test riêng thuật toán này
     */
    public static void main(String[] args) throws IOException {
        // Test với retail dataset
        ExperimentLPPGrowth_Parallel exp = new ExperimentLPPGrowth_Parallel(
            "retail", "retail.txt", 10, 50, 3, true
        );

        // Tạo output directory
        new java.io.File(OUT_DIR).mkdirs();

        exp.run();
    }
}
