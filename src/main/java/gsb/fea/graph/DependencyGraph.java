package gsb.fea.graph;

import gsb.fea.model.ConstraintSet;
import gsb.fea.model.DofMapping;
import gsb.fea.model.Element;
import gsb.fea.model.LocalMatrix;
import gsb.fea.model.Node;
import gsb.fea.model.ProjectVersion;
import gsb.fea.model.Renumber;
import gsb.fea.util.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Content-hash dependency tracking. Every derivation records the hash of each source
 * entity it read; validity is decided by re-hashing the same entities on a (possibly
 * newer) version. A change propagates only to derivations and matrix entries whose
 * recorded inputs actually changed.
 */
public final class DependencyGraph {

    public static Map<String, String> hashesOf(ProjectVersion version) {
        Map<String, String> hashes = new TreeMap<>();
        for (Node node : version.nodes.values()) {
            hashes.put(node.uid, hash(Json.write(node)));
        }
        for (Element element : version.elements.values()) {
            hashes.put(element.uid, hash(Json.write(element)));
        }
        for (LocalMatrix matrix : version.matrices.values()) {
            hashes.put(matrix.uid, hash(Json.write(matrix)));
        }
        for (Map.Entry<String, DofMapping> entry : version.mappings.entrySet()) {
            hashes.put("MAP:" + entry.getKey(), hash(Json.write(entry.getValue())));
        }
        for (Map.Entry<String, ConstraintSet> entry : version.constraintSets.entrySet()) {
            hashes.put("BC:" + entry.getKey(), hash(Json.write(entry.getValue())));
        }
        for (Map.Entry<String, Renumber> entry : version.renumbers.entrySet()) {
            hashes.put("REN:" + entry.getKey(), hash(Json.write(entry.getValue())));
        }
        hashes.put("SEL:mapping", String.valueOf(version.selectedMappingRevision));
        hashes.put("SEL:constraints", String.valueOf(version.selectedConstraintRevision));
        hashes.put("SEL:renumber", String.valueOf(version.selectedRenumberRevision));
        return hashes;
    }

    public static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                out.append(String.format("%02x", bytes[i]));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Which recorded inputs changed between the derivation and the given version. */
    public static List<String> changedInputs(Map<String, String> recordedHashes, ProjectVersion version) {
        Map<String, String> current = hashesOf(version);
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> recorded : recordedHashes.entrySet()) {
            String now = current.get(recorded.getKey());
            if (now == null) {
                changed.add(recorded.getKey() + " (removed)");
            } else if (!now.equals(recorded.getValue()) && !recorded.getKey().startsWith("SEL:")) {
                changed.add(recorded.getKey());
            } else if (!now.equals(recorded.getValue())) {
                changed.add(recorded.getKey());
            }
        }
        return changed;
    }

    public static boolean stillValid(Map<String, String> recordedHashes, ProjectVersion version) {
        return changedInputs(recordedHashes, version).isEmpty();
    }

    /**
     * Maps changed source entities to the global dofs they can influence, so a recompute
     * touches only affected matrix entries and can justify why the rest stay valid.
     */
    public static final class AffectedAnalysis {
        public List<String> changedEntities = new ArrayList<>();
        public java.util.Set<String> affectedDofs = new TreeSet<>();
        public java.util.Set<String> affectedElementIds = new TreeSet<>();
        public boolean displayOnlyChange;

        public String explain() {
            if (changedEntities.isEmpty()) {
                return "no recorded input changed; previous result remains valid";
            }
            if (displayOnlyChange) {
                return "only renumbering changed: display indices recomputed, "
                        + "physical values stay valid";
            }
            return "changed inputs " + changedEntities + " affect dofs " + affectedDofs
                    + "; only entries touching those dofs are recomputed";
        }
    }

    public static AffectedAnalysis analyze(Map<String, String> recordedHashes,
                                           ProjectVersion previous, ProjectVersion next) {
        AffectedAnalysis analysis = new AffectedAnalysis();
        Map<String, String> before = hashesOf(previous);
        Map<String, String> after = hashesOf(next);
        TreeSet<String> keys = new TreeSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        boolean renumberOnly = true;
        for (String key : keys) {
            String a = before.get(key);
            String b = after.get(key);
            if (a == null || b == null || !a.equals(b)) {
                analysis.changedEntities.add(key);
                if (!key.startsWith("REN:") && !key.equals("SEL:renumber")) {
                    renumberOnly = false;
                }
            }
        }
        analysis.displayOnlyChange = renumberOnly && !analysis.changedEntities.isEmpty();

        for (String key : analysis.changedEntities) {
            if (key.startsWith("E")) {
                Element element = next.elements.get(key);
                if (element != null) {
                    analysis.affectedElementIds.add(key);
                }
            } else if (key.startsWith("LM:")) {
                String elementId = key.split(":")[1];
                analysis.affectedElementIds.add(elementId);
            } else if (key.startsWith("MAP:") || key.startsWith("BC:") || key.startsWith("SEL:")) {
                // Mapping/constraint revisions may touch any dof; mark conservatively.
                DofMapping mapping = next.selectedMapping();
                if (mapping != null) {
                    for (DofMapping.MappingEntry entry : mapping.entries) {
                        analysis.affectedDofs.add(entry.globalDofId);
                    }
                }
                ConstraintSet constraints = next.selectedConstraints();
                if (constraints != null) {
                    for (ConstraintSet.Constraint constraint : constraints.constraints) {
                        analysis.affectedDofs.add(constraint.globalDofId);
                    }
                }
            }
        }
        DofMapping mapping = next.selectedMapping();
        if (mapping != null) {
            for (DofMapping.MappingEntry entry : mapping.entries) {
                if (analysis.affectedElementIds.contains(entry.elementId)) {
                    analysis.affectedDofs.add(entry.globalDofId);
                }
            }
        }
        return analysis;
    }

    public static Map<String, String> assemblyInputs(ProjectVersion version) {
        Map<String, String> all = hashesOf(version);
        Map<String, String> inputs = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : all.entrySet()) {
            inputs.put(entry.getKey(), entry.getValue());
        }
        return inputs;
    }

    private DependencyGraph() {
    }
}
