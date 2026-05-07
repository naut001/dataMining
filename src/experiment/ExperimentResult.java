package experiment;

/**
 * Data Class: Experiment Result
 * 
 * This class is a simple container used to store the aggregated metrics
 * for a single execution of any LPPM (Locally Periodic Pattern Mining) algorithm.
 * Creating a standalone data object allows us to safely handle OutOfMemory errors
 * by returning default/negative values (-1) without crashing the entire benchmark suite.
 */
public class ExperimentResult {
    public final String algorithmName; // e.g., LPP-Growth, LPPM-Breadth
    public final String datasetName;   // retail or kosarak
    public final int maxPer;           // Maximum Periodicity parameter
    public final int minDur;           // Minimum Duration parameter
    public final int maxSoPer;         // Max Spillover of Periods
    public final long runtimeMs;       // Total execution time in milliseconds
    public final double maxMemoryMB;   // Maximum memory usage recorded via MemoryLogger
    public final long patternCount;    // Total number of periodic patterns discovered

    public ExperimentResult(String algorithmName, String datasetName,
                            int maxPer, int minDur, int maxSoPer,
                            long runtimeMs, double maxMemoryMB, long patternCount) {
        this.algorithmName = algorithmName;
        this.datasetName = datasetName;
        this.maxPer = maxPer;
        this.minDur = minDur;
        this.maxSoPer = maxSoPer;
        this.runtimeMs = runtimeMs;
        this.maxMemoryMB = maxMemoryMB;
        this.patternCount = patternCount;
    }

    /** 
     * Formats the collected statistics as a string to be written into a CSV file.
     * Uses Locale.US to ensure the decimal separator for Max Memory is a period (.),
     * which prevents parsing errors when importing the CSV into Excel, Pandas, or R.
     */
    public String toCsvRow() {
        return String.format(java.util.Locale.US,
            "%s,%s,%d,%d,%d,%d,%.2f,%d",
            algorithmName, datasetName,
            maxPer, minDur, maxSoPer,
            runtimeMs, maxMemoryMB, patternCount
        );
    }
}
