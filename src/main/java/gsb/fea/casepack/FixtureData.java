package gsb.fea.casepack;

import gsb.fea.model.ConstraintSet;
import gsb.fea.model.DofMapping;
import gsb.fea.model.Element;
import gsb.fea.model.LocalMatrix;
import gsb.fea.model.Node;
import gsb.fea.model.ProjectVersion;
import gsb.fea.model.Renumber;
import gsb.fea.util.Ids;
import gsb.fea.util.RawDecimal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-authored engineering fixture for "稀疏装配账：有限元条目、约束消元与矩阵来源追踪".
 * All physical quantities carry units or a coordinate frame; raw text is preserved.
 */
public final class FixtureData {

    static final long T0 = 1_758_000_000_000L;

    private FixtureData() {
    }

    // -------------------------------------------------------------- raw inputs

    static RawDecimal v(String raw, String unit) {
        return new RawDecimal(raw, unit);
    }

    static Node node(int number, String x, String y, String... axes) {
        Node node = new Node(Ids.node(number), number, v(x, "m"), v(y, "m"), null);
        node.coordinateFrame = "GLOBAL-XYZ";
        node.dofAxes = new ArrayList<>(List.of(axes));
        return node;
    }

    static Element element(int number, String type, int[] nodeNumbers,
                           List<String> localDofOrder) {
        List<String> nodeUids = new ArrayList<>();
        for (int n : nodeNumbers) {
            nodeUids.add(Ids.node(n));
        }
        return new Element(Ids.element(number), number, type, nodeUids, localDofOrder);
    }

    static List<String> dofs2d(int... nodeNumbers) {
        List<String> order = new ArrayList<>();
        for (int n : nodeNumbers) {
            order.add(Ids.node(n) + ".ux");
            order.add(Ids.node(n) + ".uy");
        }
        return order;
    }

    static LocalMatrix matrix(int elementNumber, String revision, int dimension,
                              boolean symmetric, Object[][] values) {
        Element elementRef = null;
        LocalMatrix matrix = new LocalMatrix();
        matrix.uid = Ids.localMatrix(Ids.element(elementNumber), revision);
        matrix.elementId = Ids.element(elementNumber);
        matrix.revision = revision;
        matrix.dimension = dimension;
        matrix.unit = "N/m";
        matrix.floatMode = "IEEE754-BINARY64";
        matrix.declaredSymmetric = symmetric;
        for (Object[] triple : values) {
            int r = (int) triple[0];
            int c = (int) triple[1];
            String raw = (String) triple[2];
            matrix.entries.add(new LocalMatrix.Entry(r, c, v(raw, "N/m")));
        }
        return matrix;
    }

    static DofMapping.MappingEntry me(int elementNumber, int localIndex,
                                      String localDof, String globalDofId) {
        return new DofMapping.MappingEntry(Ids.element(elementNumber), localIndex,
                localDof, globalDofId);
    }

    static ConstraintSet.Constraint bc(String uid, String dofId, ConstraintSet.Kind kind,
                                       String raw, ConstraintSet.Strategy strategy) {
        ConstraintSet.Constraint constraint = new ConstraintSet.Constraint();
        constraint.uid = uid;
        constraint.globalDofId = dofId;
        constraint.kind = kind;
        constraint.value = v(raw, raw.equals("0") ? "N/A" : "m");
        constraint.strategy = strategy;
        return constraint;
    }

    // ------------------------------------------------------------ the mesh

    static List<Node> meshNodes() {
        return new ArrayList<>(List.of(
                node(1, "0", "0", "ux", "uy"),
                node(2, "1", "0", "ux", "uy"),
                node(3, "2", "0", "ux", "uy"),
                node(4, "1", "1", "ux", "uy"),
                node(5, "3", "3", "ux", "uy")
        ));
    }

    static List<Element> meshElements() {
        return new ArrayList<>(List.of(
                element(1, "BAR2D", new int[]{1, 2},
                        dofs2d(1, 2)),
                element(2, "BAR2D", new int[]{2, 3},
                        dofs2d(2, 3)),
                element(3, "SPRING1D", new int[]{2},
                        List.of("N2.ux")),
                element(7, "SPRING1D-COUPLED", new int[]{2},
                        List.of("N2.ux", "N2.ux")),
                element(10, "COUPLER3D-DEGENERATE", new int[]{4, 4},
                        List.of("N4.ux", "N4.uy", "N4.ux"))
        ));
    }

    static Object[][] e1Matrix() {
        return new Object[][]{
                {0, 0, "2"}, {0, 2, "-1"},
                {1, 1, "2"}, {1, 3, "-2"},
                {2, 0, "-1"}, {2, 2, "1"},
                {3, 1, "-2"}, {3, 3, "2"}
        };
    }

    static Object[][] e2MatrixV1() {
        return new Object[][]{
                {0, 0, "3"}, {0, 2, "-1.5"},
                {1, 1, "3"}, {1, 3, "-3"},
                {2, 0, "-1.2"}, {2, 2, "1"},
                {3, 1, "-3"}, {3, 3, "3"}
        };
    }

    static Object[][] e2MatrixV3(String crossValue) {
        return new Object[][]{
                {0, 0, "3"}, {0, 2, crossValue},
                {1, 1, "3"}, {1, 3, "-3"},
                {2, 0, "-1.2"}, {2, 2, "1"},
                {3, 1, "-3"}, {3, 3, "3"}
        };
    }

    static DofMapping mappingRev1() {
        DofMapping mapping = new DofMapping();
        mapping.revision = "MAP-1";
        List<DofMapping.MappingEntry> entries = new ArrayList<>();
        // E1: local [N1.ux,N1.uy,N2.ux,N2.uy] -> identity
        for (int i = 0; i < 2; i++) {
            int nodeNumber = i == 0 ? 1 : 2;
            entries.add(me(1, i * 2, Ids.node(nodeNumber) + ".ux", Ids.node(nodeNumber) + ".ux"));
            entries.add(me(1, i * 2 + 1, Ids.node(nodeNumber) + ".uy", Ids.node(nodeNumber) + ".uy"));
        }
        // E2: local [N2.ux,N2.uy,N3.ux,N3.uy]
        int[] e2Nodes = {2, 3};
        for (int i = 0; i < 2; i++) {
            int nodeNumber = e2Nodes[i];
            entries.add(me(2, i * 2, Ids.node(nodeNumber) + ".ux", Ids.node(nodeNumber) + ".ux"));
            entries.add(me(2, i * 2 + 1, Ids.node(nodeNumber) + ".uy", Ids.node(nodeNumber) + ".uy"));
        }
        entries.add(me(3, 0, "N2.ux", "N2.ux"));
        entries.add(me(7, 0, "N2.ux", "N2.ux"));
        entries.add(me(7, 1, "N2.ux", "N2.ux"));
        // E10: duplicate dof - both local slots land on N4.ux
        entries.add(me(10, 0, "N4.ux", "N4.ux"));
        entries.add(me(10, 1, "N4.uy", "N4.uy"));
        entries.add(me(10, 2, "N4.ux", "N4.ux"));
        mapping.entries = entries;
        return mapping;
    }

    static ConstraintSet constraintRev1() {
        ConstraintSet set = new ConstraintSet();
        set.revision = "BC-1";
        set.constraints.add(bc("BC-001", "N1.ux", ConstraintSet.Kind.FIXED,
                "0", ConstraintSet.Strategy.ELIMINATION));
        set.constraints.add(bc("BC-002", "N1.uy", ConstraintSet.Kind.FIXED,
                "0", ConstraintSet.Strategy.ELIMINATION));
        set.constraints.add(bc("BC-003", "N4.uy", ConstraintSet.Kind.PRESCRIBED,
                "0.002", ConstraintSet.Strategy.ELIMINATION));
        return set;
    }

    static ConstraintSet constraintRev2() {
        ConstraintSet set = constraintRev1();
        set.revision = "BC-2";
        set.constraints.removeIf(constraint -> constraint.uid.equals("BC-003"));
        set.constraints.add(bc("BC-003", "N4.uy", ConstraintSet.Kind.PRESCRIBED,
                "0.003", ConstraintSet.Strategy.ELIMINATION));
        return set;
    }

    static ConstraintSet constraintRev3Mixed() {
        ConstraintSet set = constraintRev1();
        set.revision = "BC-3";
        set.constraints.add(bc("BC-004", "N3.ux", ConstraintSet.Kind.FIXED,
                "0", ConstraintSet.Strategy.PENALTY));
        return set;
    }

    static ConstraintSet constraintRev4Conflict() {
        ConstraintSet set = new ConstraintSet();
        set.revision = "BC-4";
        set.constraints.add(bc("BC-005", "N4.ux", ConstraintSet.Kind.FIXED,
                "0", ConstraintSet.Strategy.ELIMINATION));
        set.constraints.add(bc("BC-006", "N4.ux", ConstraintSet.Kind.PRESCRIBED,
                "0.005", ConstraintSet.Strategy.ELIMINATION));
        return set;
    }

    static Renumber renumberRev1() {
        Renumber renumber = new Renumber();
        renumber.revision = "REN-1";
        renumber.rationale = "reverse the display order of the mapped dofs (indices only)";
        int index = 0;
        for (String dof : List.of("N3.ux", "N3.uy", "N2.ux", "N2.uy", "N1.ux",
                "N1.uy", "N4.ux", "N4.uy")) {
            renumber.dofToNewIndex.put(dof, index++);
        }
        return renumber;
    }

    static Object[][] e3Matrix() {
        return new Object[][]{
                {0, 0, "1E16"}
        };
    }

    static Object[][] e7Matrix() {
        return new Object[][]{
                {0, 0, "-1E16"}, {1, 1, "1"}
        };
    }

    static Object[][] e10Matrix() {
        return new Object[][]{
                {0, 0, "5"}, {0, 2, "-5"},
                {1, 1, "5"},
                {2, 0, "-5"}, {2, 2, "5"}
        };
    }

    // ------------------------------------------------------------ versions

    static ProjectVersion baseline() {
        ProjectVersion version = new ProjectVersion("v1", null, "baseline-import", T0);
        version.author = "engineering";
        version.editSummary = "initial engineering import: connectivity, local matrices, "
                + "dof mapping and boundary constraints with units and float modes";
        version.nodes = new LinkedHashMap<>();
        for (Node node : meshNodes()) {
            version.nodes.put(node.uid, node);
        }
        version.elements = new LinkedHashMap<>();
        for (Element element : meshElements()) {
            version.elements.put(element.uid, element);
        }
        addMatrix(version, matrix(1, "LM-1", 4, false, e1Matrix()));
        addMatrix(version, matrix(2, "LM-1", 4, false, e2MatrixV1()));
        addMatrix(version, matrix(3, "LM-1", 1, true, e3Matrix()));
        addMatrix(version, matrix(7, "LM-1", 2, true, e7Matrix()));
        addMatrix(version, matrix(10, "LM-1", 3, false, e10Matrix()));
        DofMapping mapping = mappingRev1();
        version.mappings.put(mapping.revision, mapping);
        for (ConstraintSet set : List.of(constraintRev1(), constraintRev3Mixed(),
                constraintRev4Conflict())) {
            version.constraintSets.put(set.revision, set);
        }
        Renumber renumber = renumberRev1();
        version.renumbers.put(renumber.revision, renumber);
        version.selectedMappingRevision = "MAP-1";
        version.selectedConstraintRevision = "BC-1";
        version.selectedRenumberRevision = null;
        version.tags = new ArrayList<>(List.of("BASELINE", "ORIGINAL-REPRESENTATION-PRESERVED"));
        return version;
    }

    private static void addMatrix(ProjectVersion version, LocalMatrix matrix) {
        version.matrices.put(matrix.uid, matrix);
    }

    /** Parameter revision: prescribed displacement 0.002 m -> 0.003 m. */
    static ProjectVersion parameterRevision(ProjectVersion parent) {
        ProjectVersion version = copy(parent, "v2", "parameter-revision",
                T0 + 1000L, "prescribed displacement BC-003 revised from 0.002 m to 0.003 m");
        ConstraintSet bc2 = constraintRev2();
        version.constraintSets.put(bc2.revision, bc2);
        version.selectedConstraintRevision = "BC-2";
        return version;
    }

    /** Local-only source change: one local matrix entry becomes unconvertible. */
    static ProjectVersion localSourceChange(ProjectVersion parent) {
        ProjectVersion version = copy(parent, "v3", "local-source-change",
                T0 + 2000L, "E2 local matrix entry (0,2) reported as 'N/A' by the solver; "
                        + "raw value kept at source, numeric derivation must diagnose it");
        version.matrices.remove(Ids.localMatrix("E2", "LM-1"));
        addMatrix(version, matrix(2, "LM-2", 4, false, e2MatrixV3("N/A")));
        return version;
    }

    static ProjectVersion conflictBranchA(ProjectVersion parent) {
        ProjectVersion version = copy(parent, "v4a", "conflict-branch-a",
                T0 + 3000L, "engineer A selects REN-1 renumbering on the same base as B");
        version.selectedRenumberRevision = "REN-1";
        version.author = "engineer-a";
        return version;
    }

    static ProjectVersion conflictBranchB(ProjectVersion parent) {
        ProjectVersion version = copy(parent, "v4b", "conflict-branch-b",
                T0 + 3000L, "engineer B selects mixed strategy constraint set BC-3 on the same base as A");
        ConstraintSet mixed = constraintRev3Mixed();
        if (!version.constraintSets.containsKey(mixed.revision)) {
            version.constraintSets.put(mixed.revision, mixed);
        }
        version.selectedConstraintRevision = "BC-3";
        version.author = "engineer-b";
        return version;
    }

    static ProjectVersion copy(ProjectVersion parent, String id, String label,
                               long createdAt, String summary) {
        ProjectVersion version = new ProjectVersion(id, parent.id, label, createdAt);
        version.author = parent.author;
        version.editSummary = summary;
        version.nodes = new LinkedHashMap<>(parent.nodes);
        version.elements = new LinkedHashMap<>(parent.elements);
        version.matrices = new LinkedHashMap<>(parent.matrices);
        version.mappings = new LinkedHashMap<>(parent.mappings);
        version.constraintSets = new LinkedHashMap<>(parent.constraintSets);
        version.renumbers = new LinkedHashMap<>(parent.renumbers);
        version.selectedMappingRevision = parent.selectedMappingRevision;
        version.selectedConstraintRevision = parent.selectedConstraintRevision;
        version.selectedRenumberRevision = parent.selectedRenumberRevision;
        version.tags = new ArrayList<>(parent.tags);
        return version;
    }
}
