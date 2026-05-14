package experiment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import algorithms.lppm.AlgoLPPMDepth_Parallel;
import tools.MemoryLogger;

/**
 * Experiment wrapper cho AlgoLPPMDepth_Parallel.
 * Giống ExperimentLPPMDepth nhưng dùng parallel algorithm.
 */
public class ExperimentLPPMDepth_Parallel {

    public static final String DATA_DIR = ExperimentLPPGrowth.DATA_DIR;
    public static final String OUT_DIR  = ExperimentLPPGrowth_Parallel.OUT_DIR;

    private final String  datasetName;
    private final String  inputPath;
    private final int     maxPer;
    private final int     minDur;
    private final int     maxSoPer;
    private final boolean selfIncrement;
    private final int     numThreads;

    public ExperimentLPPMDepth_Parallel(String datasetName, String inputFile,
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

    public ExperimentResult run() throws IOException {
        String label      = datasetName + "__maxPer" + maxPer + "__minDur" + minDur + "__maxSoPer" + maxSoPer;
        String outputFile = OUT_DIR + "LPPMDepth_Parallel__" + label + "__output.txt";
        String statsFile  = OUT_DIR + "LPPMDepth_Parallel__" + label + "__stats.txt";

        System.out.println("\n========================================================");
        System.out.printf ("  EXPERIMENT: LPPM-Depth PARALLEL (%d threads)%n", numThreads);
        System.out.println("========================================================");
        System.out.println("  Dataset : " + datasetName + " (" + inputPath + ")");
        System.out.printf ("  Params  : maxPer=%d minDur=%d maxSoPer=%d threads=%d%n",
                            maxPer, minDur, maxSoPer, numThreads);
        System.out.println("  Output  : " + outputFile);

        MemoryLogger.getInstance().reset();
        long startTime = System.currentTimeMillis();

        try {
            AlgoLPPMDepth_Parallel algo = new AlgoLPPMDepth_Parallel(numThreads);
            algo.runAlgorithm(inputPath, outputFile, maxPer, minDur, maxSoPer, selfIncrement);

            long   runtime      = System.currentTimeMillis() - startTime;
            double maxMemoryMB  = MemoryLogger.getInstance().getMaxMemory();
            long   patternCount = countLines(outputFile);

            algo.printStats();
            System.out.printf("  [Wrapper] Runtime: %d ms | Patterns: %d%n", runtime, patternCount);

            ExperimentResult result = new ExperimentResult(
                "LPPM-Depth-Parallel", datasetName, maxPer, minDur, maxSoPer,
                runtime, maxMemoryMB, patternCount
            );
            ExperimentLPPGrowth.writeStats(statsFile, result);
            System.out.println("  OK: Stats -> " + statsFile);
            return result;

        } catch (OutOfMemoryError oom) {
            System.err.printf("  WARNING: OOM! '%s' too large for LPPM-Depth-Parallel%n", datasetName);
            ExperimentResult oomResult = new ExperimentResult(
                "LPPM-Depth-Parallel", datasetName, maxPer, minDur, maxSoPer,
                -1L, -1.0, -1L
            );
            ExperimentLPPGrowth.writeStats(statsFile, oomResult);
            return oomResult;
        }
    }

    private long countLines(String path) {
        try { return Files.lines(Paths.get(path)).count(); }
        catch (IOException e) { return -1L; }
    }
}
