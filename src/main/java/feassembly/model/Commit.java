package feassembly.model;

import org.json.JSONObject;

/**
 * One immutable event on a branch. payload is the raw edit instruction (kept
 * verbatim). derivedJson is the full derived result computed after applying
 * the commit; it is null only while the write is unfinished (crash window).
 */
public final class Commit {
    public final String commitId;
    public final String branch;
    public final String parentId;
    public final int seq;
    public final String eventType;
    public final String author;
    public final String createdAt;
    public final String payloadJson;
    public final String stateBeforeId;
    public String stateAfterId;
    public String derivedJson;
    public String status; // PENDING / COMPLETE / SUPERSEDED
    public String label;

    public Commit(String commitId, String branch, String parentId, int seq,
                  String eventType, String author, String createdAt,
                  String payloadJson, String stateBeforeId, String stateAfterId,
                  String derivedJson, String status, String label) {
        this.commitId = commitId;
        this.branch = branch;
        this.parentId = parentId;
        this.seq = seq;
        this.eventType = eventType;
        this.author = author;
        this.createdAt = createdAt;
        this.payloadJson = payloadJson;
        this.stateBeforeId = stateBeforeId;
        this.stateAfterId = stateAfterId;
        this.derivedJson = derivedJson;
        this.status = status;
        this.label = label;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject()
                .put("commitId", commitId)
                .put("branch", branch)
                .put("parentId", parentId == null ? JSONObject.NULL : parentId)
                .put("seq", seq)
                .put("eventType", eventType)
                .put("author", author)
                .put("createdAt", createdAt)
                .put("payload", new JSONObject(payloadJson))
                .put("stateBeforeId", stateBeforeId)
                .put("stateAfterId", stateAfterId)
                .put("status", status)
                .put("label", label);
        if (derivedJson != null) o.put("derived", new JSONObject(derivedJson));
        return o;
    }

    public JSONObject payload() {
        return new JSONObject(payloadJson);
    }
}
