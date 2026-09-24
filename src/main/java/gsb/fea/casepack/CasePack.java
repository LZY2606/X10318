package gsb.fea.casepack;

import gsb.fea.model.ProjectVersion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Offline domain case pack: versioned engineering material plus a replay script. */
public final class CasePack {
    public String packId = "fea-sparse-ledger";
    public String title = "稀疏装配账：有限元条目、约束消元与矩阵来源追踪";
    public String narrative = "";
    public List<VersionEntry> versions = new ArrayList<>();
    public List<ReplayStep> replay = new ArrayList<>();

    public static final class VersionEntry {
        public String eventType;
        public String actor;
        public String rationale;
        public ProjectVersion version;
    }

    public static final class ReplayStep {
        public int n;
        public String action;
        public String id;
        public String on;
        public String version;
        public String summation;
        public String strategy;
        public String renumber;
        public String constraintRevision;
        public String choose;
        public String conflictId;
        public String actor;
        public String title;
        public Map<String, Object> expect = new LinkedHashMap<>();
    }

    public VersionEntry versionEntry(String id) {
        for (VersionEntry entry : versions) {
            if (entry.version.id.equals(id)) {
                return entry;
            }
        }
        return null;
    }
}
