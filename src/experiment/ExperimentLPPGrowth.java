package experiment;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import algorithms.lppgrowth.AlgoLPPGrowth;
import tools.MemoryLogger;

/**
 * Experiment Class: LPP-Growth Algorithm
 * 
 * This class serves as a benchmark wrapper for the LPP-Growth algorithm.
 * LPP-Growth uses a Pattern-Growth approach similar to FP-Growth.
 * Instead of generating candidates level-by-level, it builds a compressed 
 * LPPTree structure in memory and uses a purely recursive mining function (mine()).
 * 
 * Performance characteristic: Usually the fastest and most memory efficient 
 * because it avoids building large Vertical Database structures (BitSets).
 */
public class ExperimentLPPGrowth {

    // Defines the input directory containing dataset files and the output directory for logs
    public static final String DATA_DIR = "src/data/";
    public static final String OUT_DIR  = "outputs/";

    private final String datasetName;
    private final String inputPath;
    private final int maxPer;         // Max allowed distance (gap) between transactions containing the pattern
    private final int minDur;         // Minimum overall duration where the pattern must frequently appear
    private final int maxSoPer;       // Maximum spill-over factor to tolerate small disruptions in periodicity
    private final boolean selfIncrement; // True if the dataset does NOT have explicit timestamps

    /**
     * Constructor initializes all parameters required to run the original SPMF algorithm.
     */
    public ExperimentLPPGrowth(String datasetName, String inputFile,
                                int maxPer, int minDur, int maxSoPer,
                                boolean selfIncrement) {
        this.datasetName   = datasetName;
        this.inputPath     = DATA_DIR + inputFile;
        this.maxPer        = maxPer;
        this.minDur        = minDur;
        this.maxSoPer      = maxSoPer;
        this.selfIncrement = selfIncrement;
    }

    /**
     * Executes the LPP-Growth algorithm and monitors System Resources.
     * 1. Resets the MemoryLogger to track maximum memory allocation.
     * 2. Starts a timer.
     * 3. Invokes the SPMF class (AlgoLPPGrowth).
     * 4. Gathers metric outputs.
     */
    public ExperimentResult run() throws IOException {
        String label = datasetName + "__maxPer" + maxPer + "__minDur" + minDur + "__maxSoPer" + maxSoPer;
        String outputFile = OUT_DIR + "LPPGrowth__" + label + "__output.txt";
        String statsFile  = OUT_DIR + "LPPGrowth__" + label + "__stats.txt";

        printHeader("LPP-Growth", label, outputFile);

        MemoryLogger.getInstance().reset();
        long startTime = System.currentTimeMillis();

        // Instantiate and run the original SPMF structure independently
        AlgoLPPGrowth algo = new AlgoLPPGrowth();
        algo.runAlgorithm(inputPath, outputFile, maxPer, minDur, maxSoPer, selfIncrement);

        long   runtime      = System.currentTimeMillis() - startTime;
        double maxMemoryMB  = MemoryLogger.getInstance().getMaxMemory();
        long   patternCount = countLines(outputFile); // Automatically count items by reading physical output lines

        algo.printStats();
        System.out.printf("  [Wrapper] Runtime (measured outside): %d ms\n", runtime);
        System.out.printf("  [Wrapper] Patterns found: %d\n", patternCount);

        // Package results to return to the Master Runner
        ExperimentResult result = new ExperimentResult(
            "LPP-Growth", datasetName, maxPer, minDur, maxSoPer,
            runtime, maxMemoryMB, patternCount
        );
        writeStats(statsFile, result);
        System.out.println("  OK: Stats -> " + statsFile);

        return result;
    }

    /** Utility function to correctly count the lines in the exact algorithm log file. */
    private long countLines(String filePath) {
        try {
            return Files.lines(Paths.get(filePath)).count();
        } catch (IOException e) {
            return -1L;
        }
    }

    /** Prettify console output */
    private void printHeader(String algoName, String label, String outputFile) {
        System.out.println("\n========================================================");
        System.out.printf( "  EXPERIMENT: %s\n", algoName);
        System.out.println("========================================================");
        System.out.println("  Dataset : " + datasetName + " (" + inputPath + ")");
        System.out.println("  Params  : maxPer=" + maxPer + " minDur=" + minDur + " maxSoPer=" + maxSoPer);
        System.out.println("  Output  : " + outputFile);
    }

    /** Record individual experiment metadata into a physical file for verification. */
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
}
