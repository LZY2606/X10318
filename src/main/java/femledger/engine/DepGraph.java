package femledger.engine;

import femledger.model.AssemblyResult;
import femledger.model.CaseVersion;
import femledger.model.ConstraintRec;
import femledger.model.Element;
import femledger.model.MappingEntry;
import femledger.model.Node;
import femledger.model.RecomputeExplanation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fine-grained dependency graph between direct source objects and derived global entries.
 *
 * Source fingerprint nodes: element:{id}, node:{id}, map:{elementId}:{localDof},
 * constraint:{id}, float-mode, renumber.
 * Derived nodes: entry:{id} plus per-element local contribution groups.
 * On a source change only entries downstream of the changed fingerprints are recomputed;
 * everything else is demonstrably still valid.
 */
public final class DepGraph {
    private final String resultId;
    private final Map<String, String> fingerprints = new LinkedHashMap<>();
    private final Map<String, Set<String>> dependsOn = new LinkedHashMap<>();
    private final Map<String, String> entryOwners = new LinkedHashMap<>();

    private DepGraph(String resultId) {
        this.resultId = resultId;
    }

    public String resultId() { return resultId; }
    public Map<String, String> fingerprints() { return fingerprints; }
    public Map<String, Set<String>> dependsOn() { return dependsOn; }
    public Map<String, String> entryOwners() { return entryOwners; }

    /** Builds the graph for a freshly assembled result. */
    public static DepGraph build(CaseVersion version, AssemblyResult result) {
        DepGraph g = new DepGraph(result.id());
        for (Node n : version.nodes()) {
            g.fingerprints.put("node:" + n.id(),
                    Fingerprints.of(List.of(n.originalId(), n.coords().stream()
                            .map(rv -> List.of(rv.id(), rv.raw(), rv.unit(), rv.datum())).toList())));
        }
        for (Element e : version.elements()) {
            g.fingerprints.put("element:" + e.id(), Fingerprints.of(List.of(
                    e.originalId(), e.type(), e.nodeIds(), e.localDofOrder(),
                    e.localMatrix().stream()
                            .map(rv -> List.of(rv.id(), rv.raw(), rv.unit(), rv.datum())).toList())));
        }
        for (MappingEntry m : version.mappings()) {
            g.fingerprints.put("map:" + m.elementId() + ":" + m.localDof(),
                    Fingerprints.of(List.of(m.elementId(), m.localDof(), m.globalDof())));
        }
        for (ConstraintRec c : version.constraints()) {
            g.fingerprints.put("constraint:" + c.id(), Fingerprints.of(List.of(
                    c.constraintVersion(), c.globalDof(), c.strategy(),
                    List.of(c.value().id(), c.value().raw(), c.value().unit()))));
        }
        g.fingerprints.put("float-mode", Fingerprints.of(version.floatMode()));
        g.fingerprints.put("renumber", Fingerprints.of(version.label() + "#renumber"));

        result.entries().forEach(entry -> {
            Set<String> deps = new LinkedHashSet<>();
            entry.contributions().forEach(c -> {
                String elementKey = "element:" + c.elementId();
                deps.add(elementKey);
                deps.add("map:" + c.elementId() + ":" + c.localRowDof());
                deps.add("map:" + c.elementId() + ":" + c.localColDof());
                g.entryOwners.put(c.id(), elementKey);
            });
            deps.add("float-mode");
            g.dependsOn.put("entry:" + entry.id(), deps);
        });
        result.reducedEntries().forEach(entry -> {
            Set<String> deps = new LinkedHashSet<>(g.dependsOn
                    .getOrDefault("entry:" + entry.id(), new LinkedHashSet<>()));
            entry.contributions().forEach(c -> deps.add("constraint:" + c.elementId()));
            g.dependsOn.put("reduced:" + entry.id(), deps);
        });
        return g;
    }

    /**
     * Propagates a change expressed as a set of new source fingerprints and returns an
     * explanation of which derived entries stayed valid and which must be recomputed.
     */
    public RecomputeExplanation propagate(Map<String, String> newFingerprints) {
        List<String> changedSources = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        fingerprints.forEach((key, oldFp) -> {
            String newFp = newFingerprints.get(key);
            if (newFp != null && !newFp.equals(oldFp)) {
                changedSources.add(key);
            } else if (newFp != null) {
                unchanged.add(key + "@" + oldFp);
            }
        });
        List<String> recomputed = new ArrayList<>();
        dependsOn.forEach((derived, deps) -> {
            for (String src : changedSources) {
                if (deps.contains(src)) {
                    recomputed.add(derived);
                    break;
                }
            }
        });
        boolean stillValid = recomputed.isEmpty();
        String reason = stillValid
                ? "All upstream fingerprints are unchanged; result " + resultId
                        + " remains valid and was not recomputed."
                : "Changed sources " + changedSources + " reach " + recomputed.size()
                        + " derived entries; only those entries (and their constraints) are recomputed.";
        return new RecomputeExplanation(stillValid, resultId, unchanged, changedSources,
                recomputed, reason);
    }

    /** Fingerprints for a version in the same shape as {@link #build} produces. */
    public static Map<String, String> sourceFingerprints(CaseVersion version) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Node n : version.nodes()) {
            out.put("node:" + n.id(), Fingerprints.of(List.of(n.originalId(), n.coords().stream()
                    .map(rv -> List.of(rv.id(), rv.raw(), rv.unit(), rv.datum())).toList())));
        }
        for (Element e : version.elements()) {
            out.put("element:" + e.id(), Fingerprints.of(List.of(
                    e.originalId(), e.type(), e.nodeIds(), e.localDofOrder(),
                    e.localMatrix().stream()
                            .map(rv -> List.of(rv.id(), rv.raw(), rv.unit(), rv.datum())).toList())));
        }
        for (MappingEntry m : version.mappings()) {
            out.put("map:" + m.elementId() + ":" + m.localDof(),
                    Fingerprints.of(List.of(m.elementId(), m.localDof(), m.globalDof())));
        }
        for (ConstraintRec c : version.constraints()) {
            out.put("constraint:" + c.id(), Fingerprints.of(List.of(
                    c.constraintVersion(), c.globalDof(), c.strategy(),
                    List.of(c.value().id(), c.value().raw(), c.value().unit()))));
        }
        out.put("float-mode", Fingerprints.of(version.floatMode()));
        out.put("renumber", Fingerprints.of(version.label() + "#renumber"));
        return out;
    }
}
