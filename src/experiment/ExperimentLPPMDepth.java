package experiment;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import algorithms.lppm.AlgoLPPMDepth2;
import tools.MemoryLogger;

/**
 * Experiment Class: LPPM-Depth Algorithm
 * 
 * This wrapper runs the LPPM-Depth solution. Structurally, it maintains the precise 
 * Vertical approach evaluated during LPPM-Breadth, but its branch methodology leverages
 * a Depth First Search (incorporating Equivalence Classes linking standard prefix components).
 * 
 * Performance characteristic: DFS mitigates candidate generation issues common to BFS,
 * drastically reducing minor memory limits (compared to Breadth), however large datasets 
 * remain problematic due to the vertical indexing structure allocating the initial DB vector map.
 */
public class ExperimentLPPMDepth {

    public static final String DATA_DIR = ExperimentLPPGrowth.DATA_DIR;
    public static final String OUT_DIR  = ExperimentLPPGrowth.OUT_DIR;

    private final String datasetName;
    private final String inputPath;
    private final int maxPer;
    private final int minDur;
    private final int maxSoPer;
    private final boolean selfIncrement;

    public ExperimentLPPMDepth(String datasetName, String inputFile,
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
        String outputFile = OUT_DIR + "LPPMDepth__" + label + "__output.txt";
        String statsFile  = OUT_DIR + "LPPMDepth__" + label + "__stats.txt";

        printHeader("LPPM-Depth (no OTS)", label, outputFile);

        MemoryLogger.getInstance().reset();
        long startTime = System.currentTimeMillis();

        try {
            // Version 2 skips OTS optimization (Only Timestamps of Single items)
            // serving as a strict standard baseline metric
            AlgoLPPMDepth2 algo = new AlgoLPPMDepth2();
            algo.runAlgorithm(inputPath, outputFile, maxPer, minDur, maxSoPer, selfIncrement);

            long   runtime      = System.currentTimeMillis() - startTime;
            double maxMemoryMB  = MemoryLogger.getInstance().getMaxMemory();
            long   patternCount = countLines(outputFile);

            algo.printStats();
            System.out.printf("  [Wrapper] Runtime (measured outside): %d ms\n", runtime);
            System.out.printf("  [Wrapper] Patterns found: %d\n", patternCount);

            ExperimentResult result = new ExperimentResult(
                "LPPM-Depth", datasetName, maxPer, minDur, maxSoPer,
                runtime, maxMemoryMB, patternCount
            );
            ExperimentLPPGrowth.writeStats(statsFile, result);
            System.out.println("  OK: Stats -> " + statsFile);
            return result;

        } catch (OutOfMemoryError oom) {
            // LPPM-Depth may also fail identically generating BitSets mappings for Kosarak.
            System.err.println("\n  WARNING: OutOfMemoryError! Dataset '" + datasetName + "' is too large for LPPM-Depth!");
            System.err.println("  Writing OOM results to stats file...");
            ExperimentResult oomResult = new ExperimentResult(
                "LPPM-Depth", datasetName, maxPer, minDur, maxSoPer,
                -1L, -1.0, -1L
            );
            writeOomStats(statsFile, oomResult);
            System.out.println("  OK: OOM Stats -> " + statsFile);
            return oomResult;
        }
    }

    private void writeOomStats(String statsFile, ExperimentResult r) throws IOException {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(statsFile))) {
            bw.write("=== LPPM-Depth - Experiment Stats ===\n");
            bw.write("Dataset         : " + r.datasetName + "\n");
            bw.write("maxPer          : " + r.maxPer + "\n");
            bw.write("minDur          : " + r.minDur + "\n");
            bw.write("maxSoPer        : " + r.maxSoPer + "\n");
            bw.write("-------------------------------------\n");
            bw.write("STATUS          : OUT OF MEMORY (OOM)\n");
            bw.write("Runtime (ms)    : N/A (OOM)\n");
            bw.write("Max Memory (MB) : > heap limit\n");
            bw.write("Patterns found  : N/A (OOM)\n");
            bw.write("Note: LPPM-Depth effectively ran out of memory indexing the initial Database Vectors.\n");
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
