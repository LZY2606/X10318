package fealedger.model;

/**
 * A machine-readable provenance event. Events are the audit trail: every import,
 * rule decision, diagnostic, derivation, human decision, replay step and
 * accept/reject action becomes an event with a stable id and typed payload.
 */
public final class Event {
    public long seq;
    public String eventId;
    public String versionId;
    public String type;
    public String actor;
    public long timestamp;
    public String basis;      // machine-readable judgment basis (rule id / hash)
    public String payloadJson;

    public Event() {}

    public Event(String eventId, String versionId, String type, String actor,
                 String basis, String payloadJson) {
        this.eventId = eventId;
        this.versionId = versionId;
        this.type = type;
        this.actor = actor;
        this.basis = basis;
        this.payloadJson = payloadJson;
        this.timestamp = System.currentTimeMillis();
    }
}
