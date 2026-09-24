package gsb.fea.engine;

import gsb.fea.model.CaseInput;
import gsb.fea.model.Contribution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dependency-graph propagation. Nodes:
 *
 * source:element:&lt;id&gt; -> contribution:* (the local matrix + mapping of one element)
 * source:constraints -> entry:* / reduction
 * source:floatmode -> aggregation (sum order)
 *
 * When a source revision changes only an element-local dependency, the element's
 * contributions are marked dirty while unrelated contributions and entries with
 * an unchanged contribution stack remain valid.
 */
public final class RecomputePlanner {

    private RecomputePlanner() {
    }

    public record Plan(Map<String, String> nodeStatus, List<String> stillValidEntries,
                       List<String> dirtyEntries, List<String> reasons) {
    }

    public static Plan plan(CaseInput previous, CaseInput next, List<Contribution> previousContributions,
                            List<String> previousEntryIds) {
        Map<String, String> status = new LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();

        boolean floatModeChanged = !previous.floatMode().equals(next.floatMode());
        status.put("source:floatmode", floatModeChanged ? "DIRTY" : "VALID");
        boolean constraintsChanged = !previous.constraints().equals(next.constraints());
        status.put("source:constraints", constraintsChanged ? "DIRTY" : "VALID");
        status.put("derived:reduction", constraintsChanged || floatModeChanged ? "DIRTY" : "VALID");

        Map<String, List<Contribution>> byEntry = new LinkedHashMap<>();
        for (Contribution c : previousContributions) {
            String key = c.globalRowKey() + "::" + c.globalColKey();
            byEntry.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
        }

        List<String> dirtyEntries = new ArrayList<>();
        List<String> stillValid = new ArrayList<>();
        for (Map.Entry<String, List<Contribution>> e : byEntry.entrySet()) {
            boolean elementDirty = false;
            for (Contribution c : e.getValue()) {
                boolean changed = elementChanged(previous, next, c.elementOriginalId());
                status.put("source:element:" + c.elementOriginalId(), changed ? "DIRTY" : "VALID");
                if (changed) {
                    elementDirty = true;
                }
            }
            String entryNode = "entry:" + e.getKey();
            if (elementDirty || floatModeChanged) {
                status.put(entryNode, "DIRTY");
                dirtyEntries.add("entry:" + e.getKey());
                reasons.add("entry:" + e.getKey()
                        + (elementDirty ? " -> a contributing element source changed" : "")
                        + (floatModeChanged ? " -> float summation mode changed" : ""));
            } else {
                status.put(entryNode, "VALID");
                stillValid.add("entry:" + e.getKey());
                reasons.add("entry:" + e.getKey()
                        + " -> all contributing source hashes unchanged; result still valid");
            }
        }
        return new Plan(status, stillValid, dirtyEntries, reasons);
    }

    private static boolean elementChanged(CaseInput previous, CaseInput next, String elementOriginalId) {
        return !Hashes.contentHash(previous.localMatrix(elementOriginalId))
                .equals(Hashes.contentHash(next.localMatrix(elementOriginalId)))
                || !Hashes.contentHash(previous.dofMap(elementOriginalId))
                .equals(Hashes.contentHash(next.dofMap(elementOriginalId)))
                || !Hashes.contentHash(elementNodeIds(previous, elementOriginalId))
                .equals(Hashes.contentHash(elementNodeIds(next, elementOriginalId)));
    }

    private static Object elementNodeIds(CaseInput input, String id) {
        for (var e : input.elements()) {
            if (e.originalId().equals(id)) {
                return e.nodeIds();
            }
        }
        return List.of();
    }
}
