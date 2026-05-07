package experiment;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import algorithms.lppm.AlgoLPPMBreadth2;
import tools.MemoryLogger;

/**
 * Experiment Class: LPPM-Breadth Algorithm (Version 2 - Share Prefix of Patterns)
 * 
 * This class serves as a benchmark wrapper for the LPPM-Breadth algorithm.
 * LPPM-Breadth uses a Level-wise (Breadth-First Search) methodology natively mapping 
 * the transactions into a Vertical Database represented by Java BitSets.
 * 
 * Performance characteristic: Extremely fast on sparse horizontal datasets, but 
 * notoriously heavy on large scale density arrays (like 1 Million elements 'kosarak')
 * often resulting in fatal OutOfMemoryError exceptions since every single item requires 
 * evaluating a BitSet equal to the total transaction span.
 */
public class ExperimentLPPMBreadth {

    public static final String DATA_DIR = ExperimentLPPGrowth.DATA_DIR;
    public static final String OUT_DIR  = ExperimentLPPGrowth.OUT_DIR;

    private final String datasetName;
    private final String inputPath;
    private final int maxPer;
    private final int minDur;
    private final int maxSoPer;
    private final boolean selfIncrement;

    public ExperimentLPPMBreadth(String datasetName, String inputFile,
                                int maxPer, int minDur, int maxSoPer,
                                boolean selfIncrement) {
        this.datasetName   = datasetName;
        this.inputPath     = DATA_DIR + inputFile;
        this.maxPer        = maxPer;
        this.minDur        = minDur;
        this.maxSoPer      = maxSoPer;
        this.selfIncrement = selfIncrement;
    }

    public ExperimentResult run() throws IOException {
        String label = datasetName + "__maxPer" + maxPer + "__minDur" + minDur + "__maxSoPer" + maxSoPer;
        String outputFile = OUT_DIR + "LPPMBreadth__" + label + "__output.txt";
        String statsFile  = OUT_DIR + "LPPMBreadth__" + label + "__stats.txt";

        printHeader("LPPM-Breadth (SPM)", label, outputFile);

        MemoryLogger.getInstance().reset();
        long startTime = System.currentTimeMillis();

        try {
            // Version 2 uses Shared Prefix methodology to increase grouping efficiency
            AlgoLPPMBreadth2 algo = new AlgoLPPMBreadth2();
            algo.runAlgorithm(inputPath, outputFile, maxPer, minDur, maxSoPer, selfIncrement);

            long   runtime      = System.currentTimeMillis() - startTime;
            double maxMemoryMB  = MemoryLogger.getInstance().getMaxMemory();
            long   patternCount = countLines(outputFile);

            algo.printStats();
            System.out.printf("  [Wrapper] Runtime (measured outside): %d ms\n", runtime);
            System.out.printf("  [Wrapper] Patterns found: %d\n", patternCount);

            ExperimentResult result = new ExperimentResult(
                "LPPM-Breadth", datasetName, maxPer, minDur, maxSoPer,
                runtime, maxMemoryMB, patternCount
            );
            ExperimentLPPGrowth.writeStats(statsFile, result);
            System.out.println("  OK: Stats -> " + statsFile);
            return result;

        } catch (OutOfMemoryError oom) {
            // Safely intercept OOM errors so our Master Runner doesn't crash.
            // This is critical to generate the final CSV comparing successful models VS failing models.
            System.err.println("\n  WARNING: OutOfMemoryError! Dataset '" + datasetName + "' is too large for LPPM-Breadth!");
            System.err.println("  Reason: BitSet vertical DB requires ~5GB RAM traversing the BFS candidates.");
            System.err.println("  Writing OOM results to stats file...");

            ExperimentResult oomResult = new ExperimentResult(
                "LPPM-Breadth", datasetName, maxPer, minDur, maxSoPer,
                -1L, -1.0, -1L   // We signify -1 layout to track the execution fatality
            );
            writeOomStats(statsFile, oomResult);
            System.out.println("  OK: OOM Stats -> " + statsFile);
            return oomResult;
        }
    }

    private void writeOomStats(String statsFile, ExperimentResult r) throws IOException {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(statsFile))) {
            bw.write("=== LPPM-Breadth - Experiment Stats ===\n");
            bw.write("Dataset         : " + r.datasetName + "\n");
            bw.write("maxPer          : " + r.maxPer + "\n");
            bw.write("minDur          : " + r.minDur + "\n");
            bw.write("maxSoPer        : " + r.maxSoPer + "\n");
            bw.write("-------------------------------------\n");
            bw.write("STATUS          : OUT OF MEMORY (OOM)\n");
            bw.write("Runtime (ms)    : N/A (OOM)\n");
            bw.write("Max Memory (MB) : > heap limit\n");
            bw.write("Patterns found  : N/A (OOM)\n");
            bw.write("Note: LPPM-Breadth ran out of memory allocating massive Vertical BitSets due to inherent BFS matrix complexity.\n");
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
        System.out.printf( "  EXPERIMENT: %s\n", algoName);
        System.out.println("========================================================");
        System.out.println("  Dataset : " + datasetName + " (" + inputPath + ")");
        System.out.println("  Params  : maxPer=" + maxPer + " minDur=" + minDur + " maxSoPer=" + maxSoPer);
        System.out.println("  Output  : " + outputFile);
    }
}
