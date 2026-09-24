package com.gsb.fea.web;

import com.gsb.fea.storage.Commit;
import com.gsb.fea.storage.Repository;
import com.gsb.fea.storage.StoredObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifies that export -> delete local db -> import preserves stable identity,
 * ordering and provenance relations: same commit ids/parents, same object urns
 * in the same ordinal order, equal fingerprints and source_commit_id links.
 */
final class ImportCheck {
    private ImportCheck() {}

    static Map<String, Object> verify(Repository original, Path importedDbFile) {
        try (Repository imported = new Repository(importedDbFile)) {
            Map<String, Object> out = new LinkedHashMap<>();
            List<String> problems = new ArrayList<>();

            if (!eq(original.currentCommitId(), imported.currentCommitId())) {
                problems.add("current pointer differs");
            }
            List<Commit> oc = original.commits();
            List<Commit> nc = imported.commits();
            if (oc.size() != nc.size()) {
                problems.add("commit count differs: " + oc.size() + " vs " + nc.size());
            }
            for (int i = 0; i < Math.min(oc.size(), nc.size()); i++) {
                Commit a = oc.get(i);
                Commit b = nc.get(i);
                if (!a.commitId().equals(b.commitId())
                        || !eq(a.parentCommitId(), b.parentCommitId())
                        || !a.digest().equals(b.digest())
                        || !a.branch().equals(b.branch())) {
                    problems.add("commit mismatch at " + i + ": " + a.commitId()
                            + " vs " + b.commitId());
                }
                List<StoredObject> oa = original.objectsOf(a.commitId());
                List<StoredObject> nb = imported.objectsOf(b.commitId());
                if (oa.size() != nb.size()) {
                    problems.add("object count differs in " + a.commitId());
                    continue;
                }
                for (int j = 0; j < oa.size(); j++) {
                    StoredObject x = oa.get(j);
                    StoredObject y = nb.get(j);
                    if (!x.urn().equals(y.urn())) {
                        problems.add("identity/order mismatch: " + x.urn() + " vs " + y.urn());
                    }
                    if (x.ord() != y.ord()) {
                        problems.add("ordinal mismatch for " + x.urn());
                    }
                    if (!x.fingerprint().equals(y.fingerprint())) {
                        problems.add("fingerprint mismatch for " + x.urn());
                    }
                    if (!eq(x.sourceCommitId(), y.sourceCommitId())) {
                        problems.add("provenance mismatch for " + x.urn());
                    }
                }
            }
            int oe = original.events(null).size();
            int ne = imported.events(null).size();
            if (oe != ne) problems.add("event count differs: " + oe + " vs " + ne);

            out.put("ok", problems.isEmpty());
            out.put("problems", problems);
            out.put("commitsCompared", Math.min(oc.size(), nc.size()));
            out.put("eventsCompared", Math.min(oe, ne));
            return out;
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
