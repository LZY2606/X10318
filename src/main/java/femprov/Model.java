package femprov;

import java.util.List;
import java.util.Map;

/** Raw import layer: everything kept verbatim from the source files. */
public final class Model {
    private Model() {}

    /** One finite element, original numbering and local dof order preserved. */
    public record RawElement(
            String origId,               // original element id, never renumbered at raw layer
            List<String> connectivity,   // original node ids
            List<String> localDofOrder,  // local dof labels in local order
            List<List<String>> matrix,   // local matrix values as raw strings
            String unit) {}

    /** Boundary constraint as imported; strategy is elimination or penalty. */
    public record RawConstraint(String dof, String value, String unit, String strategy) {}

    /** A full raw import snapshot. */
    public record RawImport(
            String importId,
            String constraintVersion,
            String floatMode,        // SEQ or PAIRWISE summation
            String coordinateBasis,  // e.g. "global-XY-m"
            List<String> declaredDofs,
            List<RawElement> elements,
            Map<String, List<String>> dofMaps,  // origElementId -> global dof ids in local order
            List<RawConstraint> constraints) {}

    /** Known unit conversion factors to SI (N, m). Unknown units stay raw. */
    public static final Map<String, Double> UNIT_TO_SI = Map.of(
            "N/m", 1.0, "kN/m", 1e3, "N/mm", 1e3,
            "N", 1.0, "kN", 1e3,
            "m", 1.0, "mm", 1e-3);

    public static Double unitFactor(String unit) {
        return UNIT_TO_SI.get(unit);
    }
}
