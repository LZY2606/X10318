package gsb.fea.assembly;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Structural + numeric comparison of two assembly derivations, by physical dof identity. */
public final class AssemblyDiff {
    public String leftDerivationId;
    public String rightDerivationId;
    public List<EntryDelta> entryDeltas = new ArrayList<>();
    public List<String> leftOnlyDiagnostics = new ArrayList<>();
    public List<String> rightOnlyDiagnostics = new ArrayList<>();

    public static AssemblyDiff of(AssemblyResult left, AssemblyResult right,
                                  String leftId, String rightId) {
        AssemblyDiff diff = new AssemblyDiff();
        diff.leftDerivationId = leftId;
        diff.rightDerivationId = rightId;
        Map<String, MatrixEntry> leftEntries = byPhysical(left);
        Map<String, MatrixEntry> rightEntries = byPhysical(right);
        java.util.TreeSet<String> positions = new java.util.TreeSet<>();
        positions.addAll(leftEntries.keySet());
        positions.addAll(rightEntries.keySet());
        for (String position : positions) {
            MatrixEntry a = leftEntries.get(position);
            MatrixEntry b = rightEntries.get(position);
            EntryDelta delta = new EntryDelta();
            delta.physicalRow = Integer.parseInt(position.split(":")[0]);
            delta.physicalCol = Integer.parseInt(position.split(":")[1]);
            delta.rowDofId = a != null ? a.globalRowDofId : b.globalRowDofId;
            delta.colDofId = a != null ? a.globalColDofId : b.globalColDofId;
            delta.leftUid = a == null ? null : a.uid;
            delta.rightUid = b == null ? null : b.uid;
            if (a == null) {
                delta.kind = "ADDED";
                delta.rightValue = b.exactValue;
                diff.entryDeltas.add(delta);
            } else if (b == null) {
                delta.kind = "REMOVED";
                delta.leftValue = a.exactValue;
                diff.entryDeltas.add(delta);
            } else if (!sameValue(a.exactValue, b.exactValue)
                    || a.contributionUids.size() != b.contributionUids.size()) {
                delta.kind = "CHANGED";
                delta.leftValue = a.exactValue;
                delta.rightValue = b.exactValue;
                delta.leftContributions = a.contributionUids.size();
                delta.rightContributions = b.contributionUids.size();
                diff.entryDeltas.add(delta);
            }
        }
        java.util.Set<String> leftDiag = codes(left);
        java.util.Set<String> rightDiag = codes(right);
        for (String code : leftDiag) {
            if (!rightDiag.contains(code)) {
                diff.leftOnlyDiagnostics.add(code);
            }
        }
        for (String code : rightDiag) {
            if (!leftDiag.contains(code)) {
                diff.rightOnlyDiagnostics.add(code);
            }
        }
        return diff;
    }

    private static Map<String, MatrixEntry> byPhysical(AssemblyResult result) {
        Map<String, MatrixEntry> map = new TreeMap<>();
        for (MatrixEntry entry : result.entries) {
            map.put(entry.physicalRow + ":" + entry.physicalCol, entry);
        }
        return map;
    }

    private static java.util.Set<String> codes(AssemblyResult result) {
        java.util.Set<String> codes = new java.util.TreeSet<>();
        for (Diagnostic diagnostic : result.diagnostics) {
            codes.add(diagnostic.code);
        }
        return codes;
    }

    private static boolean sameValue(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }

    public static final class EntryDelta {
        public int physicalRow;
        public int physicalCol;
        public String rowDofId;
        public String colDofId;
        public String kind;
        public String leftUid;
        public String rightUid;
        public BigDecimal leftValue;
        public BigDecimal rightValue;
        public int leftContributions;
        public int rightContributions;
    }
}
