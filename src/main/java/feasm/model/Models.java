package feasm.model;

import java.util.List;
import java.util.Map;

/**
 * Immutable domain model. Everything that originates from the engineering team
 * keeps its original textual representation ({@link RawValue}); only the derived
 * assembly layer performs normalization.
 */
public final class Models {
    private Models() {}

    /** A number plus the unit / coordinate datum it was supplied in. */
    public record Quantity(String text, Double numeric, String unit, String frame, String diagnose) {
        public static Quantity raw(String text, String unit) {
            return new Quantity(text, null, unit, null, null);
        }
        public static Quantity of(double v, String unit) {
            return new Quantity(Double.toString(v), v, unit, null, null);
        }
        public Quantity withDiagnose(String d) {
            return new Quantity(text, numeric, unit, frame, d);
        }
    }

    /** Mesh node: stable identity + coordinates kept in their original frame. */
    public record Node(String id, String originalNumber, Map<String, Quantity> coords) {}

    /** Element connectivity. ldfOrder is the local-DOF order exactly as imported. */
    public record Element(String id, String originalNumber, String type, List<String> nodeIds,
                          List<String> ldfOrder, boolean degenerate, Quantity degenerateMeasure) {}

    /** One cell of a local matrix, keyed by local DOF labels from ldfOrder. */
    public record LocalEntry(String rowLdf, String colLdf, RawValue value) {}

    /** Local matrix payload for one element, one revision. */
    public record LocalMatrix(String elementId, String revision, List<LocalEntry> entries) {}

    /** local DOF label -> global DOF id (a global DOF is node:component). */
    public record DofMap(String id, String revision, Map<String, String> map) {}

    public record Constraint(String dofId, String kind, RawValue value, String strategy) {}

    /** A set of boundary constraints, versioned and tied to one strategy. */
    public record ConstraintSet(String id, String version, String strategy, List<Constraint> constraints) {}

    /** Versioned permutation from physical global DOF id to a compact index. */
    public record Renumbering(String id, String revision, Map<String, Integer> permutation, String rationale) {}

    /** One scattered scalar contribution: (element, local row/col) -> (global row/col). */
    public record Contribution(String contribId, String elementId, String originalNumber,
                               String rowLdf, String colLdf, int gRow, int gCol,
                               double value, String rawText, int orderIndex, String runId) {}

    /** Deterministically summed aggregate for one global matrix position. */
    public record AggregatedEntry(int row, int col, double sum, List<String> contribIds,
                                  String summationMode, List<Double> addends) {}

    /** A diagnostic raised during assembly; never silently swallowed. */
    public record Diagnostic(String code, String severity, String objectId, String message,
                             List<String> relatedIds, String runId) {}

    /** Result of one assembly run over a version. */
    public record AssemblyRun(String runId, String versionId, String branch, String strategy,
                              String summationMode, String renumberingId,
                              List<String> dofOrder, List<Contribution> contributions,
                              List<AggregatedEntry> entries, List<Diagnostic> diagnostics,
                              String status, String rejectReason, Map<String, Object> parameters) {}

    /** A single-source perturbation trial for the counterexample archive. */
    public record ArchiveTrial(String trialId, String baselineVersionId, String changedSource,
                               String changeKind, String expectScope,
                               String acceptedRunId, List<String> rejectedRunIds,
                               List<MachineEvent> findings, String outcome) {}

    /** Machine-readable judgement record attached to events / trials. */
    public record MachineEvent(String code, String verdict, List<String> affectedObjectIds,
                               String rationale, Map<String, String> facts) {}

    /** Immutable commit pointer for one version state. */
    public record VersionRef(String commitId, String parentCommitId, String createdBy, String message,
                             String nodesId, String elementsId, String matricesId, String mapId,
                             String constraintsId, String renumberingId, String configId,
                             String branch, long seq, long createdAt) {}

    /** Append-only ledger row. */
    public record LedgerEvent(String eventId, String seq, String branch, String type,
                              String baseCommitId, String newCommitId, String author,
                              String summary, String payloadJson, String machineEventsJson,
                              long createdAt, String conflictStatus) {}
}
