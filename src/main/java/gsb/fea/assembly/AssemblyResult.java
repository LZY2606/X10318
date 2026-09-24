package gsb.fea.assembly;

import gsb.fea.model.ConstraintSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Result of one assembly derivation (possibly blocked, possibly carrying legal candidates). */
public final class AssemblyResult {
    public String versionId;
    public AssemblyConfig config = new AssemblyConfig();
    public int physicalDimension;
    public int displayedDimension;
    public String globalDofUnit = "";
    public String matrixUnit = "";
    public String floatMode = "";
    public Map<Integer, String> indexToDof = new LinkedHashMap<>();
    public Map<Integer, Integer> physicalToDisplayed = new LinkedHashMap<>();
    public Map<String, Integer> dofToPhysical = new LinkedHashMap<>();
    public List<ContributionItem> contributions = new ArrayList<>();
    public List<MatrixEntry> entries = new ArrayList<>();
    public List<ConstraintEffect> constraintEffects = new ArrayList<>();
    public List<Diagnostic> diagnostics = new ArrayList<>();
    public boolean complete = true;

    /** Legal alternative rule candidates retained when the input is not uniquely decidable. */
    public List<Candidate> legalCandidates = new ArrayList<>();

    public boolean blocked() {
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.severity == Diagnostic.Severity.BLOCKED) {
                return true;
            }
        }
        return false;
    }

    public static final class Candidate {
        public String label;
        public String reason;
        public ConstraintSet.Strategy strategy;
        public String summation;
        public String renumberRevision;

        public Candidate() {
        }

        public Candidate(String label, String reason) {
            this.label = label;
            this.reason = reason;
        }
    }
}
