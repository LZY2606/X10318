package gsb.fea.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The full, immutable source bundle for one assembly derivation, exactly as
 * imported: element connectivity, local matrices, dof mapping and boundary
 * constraints together with units, coordinate bases and the float/ordering
 * mode. Nothing here is normalized away; derived layers (numbering, assembly)
 * live elsewhere.
 */
public record CaseInput(String caseId,
                        String label,
                        String dofKind,
                        String matrixUnit,
                        String rhsUnit,
                        Map<String, Quantity> parameters,
                        List<Node> nodes,
                        List<Element> elements,
                        List<LocalMatrix> localMatrices,
                        List<DofMap> dofMaps,
                        List<ConstraintDef> constraints,
                        FloatMode floatMode) {

    public LocalMatrix localMatrix(String elementOriginalId) {
        for (LocalMatrix m : localMatrices) {
            if (m.elementOriginalId().equals(elementOriginalId)) {
                return m;
            }
        }
        return null;
    }

    public DofMap dofMap(String elementOriginalId) {
        for (DofMap m : dofMaps) {
            if (m.elementOriginalId().equals(elementOriginalId)) {
                return m;
            }
        }
        return null;
    }

    public static Map<String, Quantity> emptyParams() {
        return new LinkedHashMap<>();
    }
}
