package gsb.fea.store;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Records {

    public static final class VersionMeta {
        public String id;
        public String parentId;
        public String label;
        public String author;
        public long createdAtMillis;
        public String editSummary;
        public String status;
    }

    public static final class DerivationRecord {
        public String id;
        public String versionId;
        public String kind;
        public String configSignature;
        public Map<String, String> inputHashes = new LinkedHashMap<>();
        public String resultJson;
        public long createdAtMillis;
        public String eventId;
    }

    public static final class EventRecord {
        public String id;
        public long seq;
        public String versionId;
        public String type;
        public String actor;
        public String rationale;
        public String payloadJson;
        public long createdAtMillis;
    }

    public static final class ConflictRecord {
        public String id;
        public String baseVersionId;
        public String versionAId;
        public String versionBId;
        public String actorA;
        public String actorB;
        public String state; // OPEN, RESOLVED
        public String resolvedVersionId;
        public String resolvedBy;
        public long createdAtMillis;
        public Long resolvedAtMillis;
    }

    private Records() {
    }
}
