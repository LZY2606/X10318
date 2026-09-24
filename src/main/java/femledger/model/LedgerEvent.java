package femledger.model;

import java.util.List;

/**
 * A machine-readable provenance event. Events record accepted AND rejected outcomes and carry the
 * machine-readable rationale for the governing rules: deterministic summation, no strategy mixing,
 * explicit isolation/asymmetry diagnosis, renumbering changes indices only.
 */
public final class LedgerEvent {
    private final String id;
    private final long seq;
    private final String type;       // IMPORT | PARAM_REVISION | LOCAL_SOURCE_CHANGE | DERIVATION | DECISION
    private final String branchId;
    private final String versionId;
    private final String resultId;
    private final String decision;   // ACCEPTED | REJECTED | CANDIDATE | OBSERVATION
    private final String title;
    private final String rationale;
    private final List<String> affectedObjectIds;
    private final String payloadJson;

    public LedgerEvent(String id, long seq, String type, String branchId, String versionId,
                       String resultId, String decision, String title, String rationale,
                       List<String> affectedObjectIds, String payloadJson) {
        this.id = id;
        this.seq = seq;
        this.type = type;
        this.branchId = branchId;
        this.versionId = versionId;
        this.resultId = resultId;
        this.decision = decision;
        this.title = title;
        this.rationale = rationale;
        this.affectedObjectIds = List.copyOf(affectedObjectIds);
        this.payloadJson = payloadJson;
    }

    public String id() { return id; }
    public long seq() { return seq; }
    public String type() { return type; }
    public String branchId() { return branchId; }
    public String versionId() { return versionId; }
    public String resultId() { return resultId; }
    public String decision() { return decision; }
    public String title() { return title; }
    public String rationale() { return rationale; }
    public List<String> affectedObjectIds() { return affectedObjectIds; }
    public String payloadJson() { return payloadJson; }
}
