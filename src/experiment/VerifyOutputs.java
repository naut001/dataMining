package experiment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

/**
 * Utility để verify kết quả sequential vs parallel.
 * So sánh sau khi sort dòng → order không quan trọng, chỉ cần nội dung giống.
 */
public class VerifyOutputs {

    /**
     * So sánh 2 file output (sort-insensitive).
     * @return true nếu cùng số dòng và cùng nội dung
     */
    public static boolean verify(String seqFile, String parFile) {
        try {
            List<String> seqLines = Files.readAllLines(Paths.get(seqFile));
            List<String> parLines = Files.readAllLines(Paths.get(parFile));

            if (seqLines.size() != parLines.size()) {
                System.err.printf("  ❌ Line count mismatch: seq=%d par=%d%n",
                                   seqLines.size(), parLines.size());
                return false;
            }

            Collections.sort(seqLines);
            Collections.sort(parLines);

            for (int i = 0; i < seqLines.size(); i++) {
                if (!seqLines.get(i).equals(parLines.get(i))) {
                    System.err.printf("  ❌ Mismatch at sorted line %d:%n", i + 1);
                    System.err.printf("     SEQ: %s%n", seqLines.get(i));
                    System.err.printf("     PAR: %s%n", parLines.get(i));
                    return false;
                }
            }

            System.out.printf("  ✅ OK: %d patterns match exactly%n", seqLines.size());
            return true;

        } catch (IOException e) {
            System.err.println("  ⚠️  Cannot read file: " + e.getMessage());
            return false;
        }
    }

    /**
     * Verify toàn bộ kết quả trên retail dataset.
     */
    public static void main(String[] args) {
        String seqDir = ExperimentLPPGrowth.OUT_DIR;
        String parDir = ExperimentLPPGrowth_Parallel.OUT_DIR;

        int[] maxPers = {10, 20, 30};
        int   minDur  = 50;
        int   maxSoPer = 3;
        String[][] datasets = {{"retail"}, {"kosarak"}};

        System.out.println("=== VERIFY: Sequential vs Parallel ===");

        for (String[] ds : datasets) {
            String dsName = ds[0];
            for (int mp : maxPers) {
                String label = dsName + "__maxPer" + mp + "__minDur" + minDur + "__maxSoPer" + maxSoPer;

                System.out.printf("%nDataset=%s maxPer=%d%n", dsName, mp);

                verify(
                    seqDir + "LPPGrowth__"    + label + "__output.txt",
                    parDir + "LPPGrowth_Parallel__"    + label + "__output.txt"
                );
                verify(
                    seqDir + "LPPMBreadth__"  + label + "__output.txt",
                    parDir + "LPPMBreadth_Parallel__"  + label + "__output.txt"
                );
                verify(
                    seqDir + "LPPMDepth__"    + label + "__output.txt",
                    parDir + "LPPMDepth_Parallel__"    + label + "__output.txt"
                );
            }
        }
    }
}
