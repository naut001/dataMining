package experiment;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Master Runner Class
 * 
 * Systematically acts as the execution orchestrator, spinning up all combined 
 * iterations dynamically (3 limit params x 2 datasets x 3 separate algorithms = 18 experiments).
 * 
 * At processing conclusion, it exports the aggregated data directly to CSV ensuring
 * analysis tasks (in MATPlotLib, R, Excel...) are effortlessly connected.
 */
public class RunAllExperiments {

    public static void main(String[] args) throws IOException {
        // Prepare Physical Output Folders
        new java.io.File(ExperimentLPPGrowth.OUT_DIR).mkdirs();

        // 1. Definition List of Test Subjects
        String[][] datasets = {
            { "retail",  "retail.txt"      },
            { "kosarak", "kosarak.dat.txt"  }
        };

        // 2. Control Experiment Hyperparameters
        int[] maxPerValues = { 10, 20, 30 }; // Scaling interval parameters evaluates structural growth capability
        int   minDur       = 50;             // Stable baseline for analysis comparison
        int   maxSoPer     = 3;              // Allow minimal disruption padding

        System.out.println("========================================================");
        System.out.println("          STARTING LOCALLY PERIODIC PATTERN MINING");
        System.out.println("========================================================");

        List<ExperimentResult> allResults = new ArrayList<>();

        for (String[] ds : datasets) {
            String dsName = ds[0];
            String dsPath = ds[1];

            System.out.println("\n## DATASET: " + dsName.toUpperCase());

            for (int maxPer : maxPerValues) {
                System.out.println("\n  -> Params: maxPer=" + maxPer + "  minDur=" + minDur + "  maxSoPer=" + maxSoPer);

                // EXPERIMENT ONE: LPP-Growth (Pattern-Growth compressed variant)
                ExperimentLPPGrowth expGr = new ExperimentLPPGrowth(dsName, dsPath, maxPer, minDur, maxSoPer, true);
                allResults.add(expGr.run());
                System.gc(); // Force memory sweep

                // EXPERIMENT TWO: LPPM-Breadth (Vertical Level-wise join variant)
                ExperimentLPPMBreadth expBr = new ExperimentLPPMBreadth(dsName, dsPath, maxPer, minDur, maxSoPer, true);
                allResults.add(expBr.run());
                System.gc(); // Force memory sweep

                // EXPERIMENT THREE: LPPM-Depth (Vertical Depth-First variant)
                ExperimentLPPMDepth expDp = new ExperimentLPPMDepth(dsName, dsPath, maxPer, minDur, maxSoPer, true);
                allResults.add(expDp.run());
                System.gc(); // Force memory sweep
            }
        }

        // Export unified benchmarks globally into a CSV matrix structure
        String csvPath = ExperimentLPPGrowth.OUT_DIR + "summary_all_experiments.csv";
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(csvPath))) {
            bw.write("Algorithm,Dataset,maxPer,minDur,maxSoPer,Runtime_ms,Memory_MB,Patterns\n");
            for (ExperimentResult r : allResults) {
                bw.write(r.toCsvRow() + "\n"); // Using formatting standard matching general data science rules
            }
        }

        System.out.println("\n========================================================");
        System.out.println("  ALL EXPERIMENTS COMPLETED SUCCESSFULLY!");
        System.out.println("  CSV Summary: " + csvPath);
        System.out.println("========================================================");
    }
}
