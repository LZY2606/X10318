package fealedger.derive;

import fealedger.domain.Q;
import fealedger.domain.Sources.*;
import fealedger.domain.Units;
import fealedger.model.Enums.ConstraintKind;
import fealedger.model.Enums.ConstraintStrategy;
import fealedger.model.Enums.FloatMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Core sparse FE assembly engine.
 *
 * Invariants:
 *  - duplicate contributions are summed in a deterministic order
 *    (element id lexicographic, then local row/col position);
 *  - ELIMINATION and PENALTY constraint strategies are never mixed;
 *  - isolated DOFs, degenerate elements, repeated DOFs and asymmetric local
 *    matrices all produce explicit diagnostics rather than silent edits;
 *  - renumbering changes only storage indices, never logical DOF identities.
 */
public final class Assembler {
    private Assembler() {}

    public record ConstraintEffect(
            String constraintId,
            String kind,
            String strategy,
            List<String> removedDofs,    // ELIMINATION only
            List<String> penaltyEntries, // entry ids touched by penalty
            String alphaRaw
    ) {}

    public record AssemblyResult(
            FloatMode floatMode,
            ConstraintStrategy strategy,
            Map<String, Integer> dofIndex,          // logical dof -> storage index
            Map<Integer, String> indexToDof,
            List<Contribution> contributions,
            List<AssembledEntry> entries,
            List<ConstraintEffect> constraintEffects,
            List<Diagnostic> diagnostics,
            boolean valid,
            /** raw unconstrained matrix entries kept for branch/diff inspection */
            Map<String, AssembledEntry> freeEntriesById
    ) {}

    // ------------------------------------------------------------ DOF index

    /** Builds logical-dof -> index map. Repeated proposed indices => duplicate diagnostic. */
    static Map<String, Integer> buildDofIndex(List<DofMapRecord> dofMaps,
                                              Map<String, Integer> renumberPlan,
                                              List<Diagnostic> diagnostics) {
        Map<String, Integer> logicalToIndex = new TreeMap<>();
        Map<Integer, List<String>> indexUsers = new TreeMap<>();
        for (DofMapRecord m : dofMaps) {
            int idx = renumberPlan != null && renumberPlan.containsKey(m.globalDofId())
                    ? renumberPlan.get(m.globalDofId()) : m.proposedIndex();
            Integer prev = logicalToIndex.putIfAbsent(m.globalDofId(), idx);
            if (prev != null && prev != idx) {
                diagnostics.add(Diagnostic.error("DOF_INDEX_CONFLICT",
                        "logical DOF " + m.globalDofId() + " mapped to two indices "
                                + prev + " and " + idx,
                        List.of(m.globalDofId())));
            }
            indexUsers.computeIfAbsent(idx, k -> new ArrayList<>()).add(m.globalDofId());
        }
        for (Map.Entry<Integer, List<String>> e : indexUsers.entrySet()) {
            long distinct = e.getValue().stream().distinct().count();
            if (distinct > 1) {
                diagnostics.add(Diagnostic.error("REPEATED_DOF_INDEX",
                        "index " + e.getKey() + " claimed by " + distinct + " logical DOFs "
                                + abbreviate(e.getValue()),
                        List.copyOf(new ArrayList<>(e.getValue()))));
            }
        }
        return logicalToIndex;
    }

    private static List<String> abbreviate(List<String> xs) {
        return xs.size() <= 8 ? xs : xs.subList(0, 8);
    }

    // ------------------------------------------------------------ element ldo f map

    /** Resolves (element, node-in-connectivity, local dof label) to logical global dof. */
    static Map<String, String> buildElementLdofMap(ElementRecord el,
                                                   List<DofMapRecord> dofMaps) {
        Map<String, String> byNodeLdof = new LinkedHashMap<>();
        for (DofMapRecord m : dofMaps) {
            if (m.elementId().equals(el.elementId())) {
                byNodeLdof.put(m.nodeId() + "|" + m.localDof(), m.globalDofId());
            }
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        int nodePos = 0;
        for (String nodeId : el.nodeIds()) {
            for (int d = 0; d < el.localDofPerNode(); d++) {
                String label = "n" + nodePos + "d" + d;
                String gd = byNodeLdof.get(nodeId + "|n" + nodePos + "d" + d);
                if (gd == null) {
                    gd = byNodeLdof.get(nodeId + "|d" + d);
                }
                resolved.put(label, gd);
            }
            nodePos++;
        }
        return resolved;
    }

    // ------------------------------------------------------------ main entry

    public static AssemblyResult assemble(AssemblyInput in, Map<String, Integer> renumberPlan) {
        List<Diagnostic> diagnostics = new ArrayList<>();

        FloatMode floatMode = parseFloatMode(in.header().floatMode(), diagnostics);
        ConstraintStrategy strategy = parseStrategy(in.header().constraintStrategy(), diagnostics);

        Map<String, NodeRecord> nodes = new TreeMap<>();
        for (NodeRecord n : in.nodes()) {
            nodes.put(n.nodeId(), n);
            String unit = n.unit() != null ? n.unit() : in.header().lengthUnit();
            String d = Units.diagnoseLength(unit);
            if (d != null) {
                diagnostics.add(Diagnostic.warn(d, "coordinate kept raw for node " + n.nodeId(),
                        List.of(n.nodeId())));
            }
        }

        Map<String, Integer> dofIndex = buildDofIndex(in.dofMaps(), renumberPlan, diagnostics);
        Map<Integer, String> indexToDof = new TreeMap<>();
        dofIndex.forEach((gd, idx) -> indexToDof.put(idx, gd));

        Map<String, LocalMatrixRecord> lmByElement = new TreeMap<>();
        for (LocalMatrixRecord lm : in.localMatrices()) {
            lmByElement.put(lm.elementId(), lm);
            String unit = lm.unitStiffness() != null ? lm.unitStiffness() : "N/m";
            if (Units.stiffness(unit) == null) {
                diagnostics.add(Diagnostic.warn("UNCONVERTIBLE_STIFFNESS_UNIT:" + unit,
                        "local matrix of element " + lm.elementId() + " kept with raw unit "
                                + unit + " (not dropped)",
                        List.of(lm.elementId())));
            }
        }

        List<Contribution> contributions = new ArrayList<>();
        Map<String, List<Contribution>> stackByEntry = new TreeMap<>();
        java.util.Set<String> mappedDofs = new java.util.TreeSet<>(dofIndex.keySet());

        List<String> elementOrder = in.elements().stream()
                .map(ElementRecord::elementId).sorted().toList();
        for (String elementId : elementOrder) {
            ElementRecord el = in.elements().stream()
                    .filter(x -> x.elementId().equals(elementId)).findFirst().orElseThrow();
            LocalMatrixRecord lm = lmByElement.get(elementId);
            if (lm == null) {
                diagnostics.add(Diagnostic.warn("MISSING_LOCAL_MATRIX",
                        "element " + elementId + " has no local matrix; skipped",
                        List.of(elementId)));
                continue;
            }
            Map<String, String> ldof = buildElementLdofMap(el, in.dofMaps());
            checkDegenerate(el, nodes, diagnostics);
            checkRepeatedDofWithinElement(elementId, ldof, diagnostics);
            checkAsymmetry(elementId, lm, diagnostics);

            List<String> order = lm.localDofOrder();
            for (int r = 0; r < order.size(); r++) {
                for (int c = 0; c < order.size(); c++) {
                    String raw = lm.valuesRaw().get(r).get(c);
                    if (isRawZero(raw)) {
                        continue; // structural zero is not a nonzero contribution
                    }
                    String rg = ldof.get(order.get(r));
                    String cg = ldof.get(order.get(c));
                    if (rg == null || cg == null) {
                        diagnostics.add(Diagnostic.error("UNMAPPED_LOCAL_DOF",
                                "element " + elementId + " local DOF "
                                        + (rg == null ? order.get(r) : order.get(c))
                                        + " has no global mapping",
                                List.of(elementId)));
                        continue;
                    }
                    int ri = dofIndex.get(rg);
                    int ci = dofIndex.get(cg);
                    String cid = "c:" + elementId + ":" + order.get(r) + "x" + order.get(c)
                            + "->" + rg + "," + cg + "=" + raw;
                    Contribution contrib = new Contribution(cid, elementId,
                            order.get(r), order.get(c), rg, cg, ri, ci, raw,
                            lm.unitStiffness() != null ? lm.unitStiffness() : "N/m");
                    contributions.add(contrib);
                    String key = entryKey(rg, cg);
                    stackByEntry.computeIfAbsent(key, k -> new ArrayList<>()).add(contrib);
                }
            }
        }

        detectIsolatedDofs(mappedDofs, stackByEntry, in.constraints(), diagnostics);

        // Deterministic order inside each stack: (element id, local row label, local col label).
        for (List<Contribution> stack : stackByEntry.values()) {
            stack.sort(Comparator.comparing(Contribution::elementId)
                    .thenComparing(Contribution::rowLocalDof)
                    .thenComparing(Contribution::colLocalDof)
                    .thenComparing(Contribution::rawValue));
        }

        Map<String, AssembledEntry> freeEntries = sumStacks(stackByEntry, dofIndex);
        ConstraintApplication applied = applyConstraints(in.constraints(), strategy,
                dofIndex, freeEntries, diagnostics);

        boolean valid = diagnostics.stream().noneMatch(d -> d.severity().equals("ERROR"));
        List<AssembledEntry> entries = new ArrayList<>(applied.entries().values());
        entries.sort(Comparator.comparingInt(AssembledEntry::rowIndex)
                .thenComparingInt(AssembledEntry::colIndex));
        contributions.sort(Comparator.comparing(Contribution::contributionId));

        return new AssemblyResult(floatMode, strategy, dofIndex, indexToDof,
                contributions, entries, applied.effects(), diagnostics, valid, freeEntries);
    }

    private static boolean isRawZero(String raw) {
        try {
            return Q.parse(raw).signum() == 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String entryKey(String rg, String cg) {
        return rg + "" + cg;
    }

    // ------------------------------------------------------------ anomaly checks

    static void checkDegenerate(ElementRecord el, Map<String, NodeRecord> nodes,
                                List<Diagnostic> diagnostics) {
        List<String> distinctNodes = new ArrayList<>();
        for (String n : el.nodeIds()) {
            if (!distinctNodes.contains(n)) {
                distinctNodes.add(n);
            }
        }
        if (distinctNodes.size() < el.nodeIds().size()) {
            diagnostics.add(Diagnostic.warn("DEGENERATE_ELEMENT_REPEATED_NODE",
                    "element " + el.elementId() + " references the same node more than once: "
                            + el.nodeIds(),
                    List.of(el.elementId())));
            return;
        }
        // Geometric degeneracy: two collinear 2-node bars is fine, but zero-length is degenerate.
        if (el.nodeIds().size() == 2) {
            NodeRecord a = nodes.get(el.nodeIds().get(0));
            NodeRecord b = nodes.get(el.nodeIds().get(1));
            if (a != null && b != null && a.coordsRaw().size() >= 2
                    && b.coordsRaw().size() >= 2) {
                try {
                    double dx = Q.parse(b.coordsRaw().get(0))
                            .subtract(Q.parse(a.coordsRaw().get(0))).toDouble();
                    double dy = Q.parse(b.coordsRaw().get(1))
                            .subtract(Q.parse(a.coordsRaw().get(1))).toDouble();
                    if (dx == 0.0 && dy == 0.0) {
                        diagnostics.add(Diagnostic.warn("DEGENERATE_ELEMENT_ZERO_LENGTH",
                                "element " + el.elementId() + " has zero length",
                                List.of(el.elementId())));
                    }
                } catch (RuntimeException ignored) {
                    // raw coordinates may be unconvertible; unit diagnostic already recorded
                }
            }
        }
    }

    static void checkRepeatedDofWithinElement(String elementId, Map<String, String> ldof,
                                              List<Diagnostic> diagnostics) {
        Map<String, List<String>> users = new LinkedHashMap<>();
        ldof.forEach((label, gd) -> {
            if (gd != null) {
                users.computeIfAbsent(gd, k -> new ArrayList<>()).add(label);
            }
        });
        users.forEach((gd, labels) -> {
            if (labels.size() > 1) {
                diagnostics.add(Diagnostic.warn("REPEATED_DOF_IN_ELEMENT",
                        "element " + elementId + " folds local DOFs " + labels
                                + " into the same global DOF " + gd
                                + " (contributions summed, not overwritten)",
                        List.of(elementId, gd)));
            }
        });
    }

    static void checkAsymmetry(String elementId, LocalMatrixRecord lm,
                               List<Diagnostic> diagnostics) {
        int n = lm.localDofOrder().size();
        List<List<String>> v = lm.valuesRaw();
        for (int r = 0; r < n; r++) {
            for (int c = r + 1; c < n; c++) {
                Q a;
                Q b;
                try {
                    a = Q.parse(v.get(r).get(c));
                    b = Q.parse(v.get(c).get(r));
                } catch (RuntimeException e) {
                    diagnostics.add(Diagnostic.error("BAD_NUMERIC_TOKEN",
                            "element " + elementId + " (" + r + "," + c + "): " + e.getMessage(),
                            List.of(elementId)));
                    return;
                }
                if (a.compareTo(b) != 0) {
                    diagnostics.add(Diagnostic.warn("LOCAL_MATRIX_ASYMMETRIC",
                            "element " + elementId + " local matrix is asymmetric at ("
                                    + lm.localDofOrder().get(r) + ","
                                    + lm.localDofOrder().get(c) + "): "
                                    + v.get(r).get(c) + " vs " + v.get(c).get(r)
                                    + " - both off-diagonal contributions are retained",
                            List.of(elementId,
                                    lm.localDofOrder().get(r), lm.localDofOrder().get(c))));
                    return;
                }
            }
        }
    }

    static void detectIsolatedDofs(java.util.Set<String> mappedDofs,
                                   Map<String, List<Contribution>> stackByEntry,
                                   List<ConstraintRecord> constraints,
                                   List<Diagnostic> diagnostics) {
        java.util.Set<String> touched = new java.util.TreeSet<>();
        for (String key : stackByEntry.keySet()) {
            String[] p = key.split("\\|\\|", 2);
            touched.add(p[0]);
            touched.add(p[1]);
        }
        for (String gd : mappedDofs) {
            if (!touched.contains(gd)) {
                diagnostics.add(Diagnostic.warn("ISOLATED_DOF",
                        "DOF " + gd + " receives no matrix contribution (isolated node/DOF)",
                        List.of(gd)));
            }
        }
    }

    // ------------------------------------------------------------ deterministic sums

    static Map<String, AssembledEntry> sumStacks(Map<String, List<Contribution>> stackByEntry,
                                                 Map<String, Integer> dofIndex) {
        Map<String, AssembledEntry> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Contribution>> e : stackByEntry.entrySet()) {
            String[] p = e.getKey().split("\\|\\|", 2);
            String rg = p[0];
            String cg = p[1];
            List<Contribution> stack = e.getValue();

            double f64 = 0.0;
            Q exact = Q.ZERO;
            for (Contribution c : stack) {
                f64 += Double.parseDouble(c.rawValue());
                exact = exact.add(Q.parse(c.rawValue()));
            }
            double rat = exact.toDouble();
            String entryId = "K[" + rg + "," + cg + "]";
            out.put(e.getKey(), new AssembledEntry(
                    entryId, dofIndex.get(rg), dofIndex.get(cg), rg, cg,
                    stack.stream().map(Contribution::contributionId).toList(),
                    f64, rat, exact.toString(), Math.abs(f64 - rat)));
        }
        return out;
    }

    // ------------------------------------------------------------ constraints

    record CandidateSet(String conflictId, String dof, List<String> candidateValuesRaw) {}

    record ConstraintApplication(
            Map<String, AssembledEntry> entries,
            List<ConstraintEffect> effects,
            List<CandidateSet> candidateSets,
            List<Contribution> penaltyContributions
    ) {}

    /** Tiny union-find over logical DOF ids. */
    private static final class UF {
        final Map<String, String> parent = new LinkedHashMap<>();

        void add(String x) {
            parent.putIfAbsent(x, x);
        }

        String find(String x) {
            add(x);
            String p = parent.get(x);
            if (!p.equals(x)) {
                p = find(p);
                parent.put(x, p);
            }
            return p;
        }

        void union(String a, String b) {
            add(a);
            add(b);
            parent.put(find(a), find(b));
        }
    }

    static ConstraintApplication applyConstraints(List<ConstraintRecord> constraints,
                                                  ConstraintStrategy strategy,
                                                  Map<String, Integer> dofIndex,
                                                  Map<String, AssembledEntry> freeEntries,
                                                  List<Diagnostic> diagnostics) {
        List<ConstraintEffect> effects = new ArrayList<>();
        List<CandidateSet> candidateSets = new ArrayList<>();
        List<Contribution> penaltyContributions = new ArrayList<>();

        UF uf = new UF();
        dofIndex.keySet().forEach(uf::add);

        // Strategy purity: a penalty alpha may only appear under PENALTY, and is then required.
        for (ConstraintRecord c : constraints) {
            boolean hasAlpha = c.penaltyAlphaRaw() != null && !c.penaltyAlphaRaw().isBlank();
            if (strategy == ConstraintStrategy.ELIMINATION && hasAlpha) {
                diagnostics.add(Diagnostic.error("CONSTRAINT_STRATEGY_MIX",
                        "constraint " + c.constraintId()
                                + " carries penalty alpha while assembly strategy is ELIMINATION; "
                                + "elimination and penalty must not be mixed",
                        List.of(c.constraintId())));
            }
            if (strategy == ConstraintStrategy.PENALTY && c.kind() == ConstraintKind.PRESCRIBED
                    && (!hasAlpha)) {
                diagnostics.add(Diagnostic.error("PENALTY_ALPHA_REQUIRED",
                        "constraint " + c.constraintId()
                                + " has no penalty alpha under PENALTY strategy",
                        List.of(c.constraintId())));
            }
            if (c.kind() == ConstraintKind.TIED) {
                if (c.dofA() == null || c.dofB() == null) {
                    diagnostics.add(Diagnostic.error("BAD_TIED_CONSTRAINT",
                            "constraint " + c.constraintId() + " needs two DOFs",
                            List.of(c.constraintId())));
                    continue;
                }
                if (!dofIndex.containsKey(c.dofA()) || !dofIndex.containsKey(c.dofB())) {
                    diagnostics.add(Diagnostic.error("CONSTRAINT_ON_UNKNOWN_DOF",
                            "constraint " + c.constraintId() + " references unmapped DOF",
                            List.of(c.constraintId(),
                                    String.valueOf(c.dofA()), String.valueOf(c.dofB()))));
                    continue;
                }
                uf.union(c.dofA(), c.dofB());
            }
        }

        // Resolve prescribed values at tie-union roots; different values => conflict, candidates kept.
        Map<String, List<ConstraintRecord>> prescribedByRoot = new LinkedHashMap<>();
        for (ConstraintRecord c : constraints) {
            if (c.kind() == ConstraintKind.PRESCRIBED) {
                if (c.dofA() == null || !dofIndex.containsKey(c.dofA())) {
                    diagnostics.add(Diagnostic.error("CONSTRAINT_ON_UNKNOWN_DOF",
                            "constraint " + c.constraintId() + " references unmapped DOF",
                            List.of(c.constraintId(), String.valueOf(c.dofA()))));
                    continue;
                }
                prescribedByRoot.computeIfAbsent(uf.find(c.dofA()), k -> new ArrayList<>()).add(c);
            }
        }
        java.util.Set<String> prescribedRoots = new java.util.TreeSet<>();
        prescribedByRoot.forEach((root, cs) -> {
            List<String> values = cs.stream().map(ConstraintRecord::valueRaw).distinct().toList();
            List<String> versions = cs.stream().map(ConstraintRecord::constraintVersion).toList();
            if (values.size() > 1) {
                String conflictId = "conflict:" + root;
                diagnostics.add(Diagnostic.error("CONSTRAINT_CONFLICT",
                        "DOF union rooted at " + root + " gets conflicting prescribed values "
                                + values + " from constraint versions " + versions
                                + "; all legal candidates retained, none applied automatically",
                        cs.stream().map(ConstraintRecord::constraintId).toList()));
                candidateSets.add(new CandidateSet(conflictId, root, values));
            } else {
                prescribedRoots.add(root);
            }
        });

        Map<String, AssembledEntry> resultEntries;
        if (strategy == ConstraintStrategy.PENALTY) {
            resultEntries = applyPenalty(constraints, dofIndex, freeEntries, uf,
                    prescribedRoots, effects, penaltyContributions, diagnostics);
        } else {
            resultEntries = applyElimination(constraints, dofIndex, freeEntries, uf,
                    prescribedRoots, effects, diagnostics);
        }
        return new ConstraintApplication(resultEntries, effects, candidateSets,
                penaltyContributions);
    }

    private static AssembledEntry mergeEntries(String key, List<AssembledEntry> parts,
                                               Map<String, Contribution> contribById,
                                               Map<String, Integer> dofIndex) {
        String[] p = key.split("\\|\\|", 2);
        List<Contribution> merged = new ArrayList<>();
        for (AssembledEntry e : parts) {
            for (String cid : e.contributionIds()) {
                merged.add(contribById.get(cid));
            }
        }
        merged.sort(Comparator.comparing(Contribution::elementId)
                .thenComparing(Contribution::rowLocalDof)
                .thenComparing(Contribution::colLocalDof)
                .thenComparing(Contribution::rawValue));
        double f64 = 0.0;
        Q exact = Q.ZERO;
        for (Contribution c : merged) {
            f64 += Double.parseDouble(c.rawValue());
            exact = exact.add(Q.parse(c.rawValue()));
        }
        double rat = exact.toDouble();
        String rg = p[0];
        String cg = p[1];
        return new AssembledEntry("K[" + rg + "," + cg + "]",
                dofIndex.getOrDefault(rg, dofIndex.get(rg)),
                dofIndex.getOrDefault(cg, dofIndex.get(cg)),
                rg, cg, merged.stream().map(Contribution::contributionId).toList(),
                f64, rat, exact.toString(), Math.abs(f64 - rat));
    }

    private static Map<String, AssembledEntry> applyElimination(
            List<ConstraintRecord> constraints, Map<String, Integer> dofIndex,
            Map<String, AssembledEntry> freeEntries, UF uf,
            java.util.Set<String> prescribedRoots, List<ConstraintEffect> effects,
            List<Diagnostic> diagnostics) {

        Map<String, List<AssembledEntry>> grouped = new LinkedHashMap<>();
        java.util.Set<String> removedEntries = new java.util.TreeSet<>();
        for (AssembledEntry e : freeEntries.values()) {
            String rr = uf.find(e.rowGlobalDof());
            String cc = uf.find(e.colGlobalDof());
            if (prescribedRoots.contains(rr) || prescribedRoots.contains(cc)) {
                removedEntries.add(e.entryId());
                continue;
            }
            grouped.computeIfAbsent(entryKey(rr, cc), k -> new ArrayList<>()).add(e);
        }

        // build contrib lookup from entries themselves
        Map<String, Contribution> contribById = new LinkedHashMap<>();
        freeEntries.values().forEach(en -> en.contributionIds().forEach(cid -> {
            // Contribution objects are reconstructed by caller-independent id not needed;
            // use first entry that contains it; raw values carried through sums already,
            // so merge via stored sums instead (see mergeBySums).
        }));

        Map<String, AssembledEntry> out = new LinkedHashMap<>();
        grouped.forEach((key, parts) -> out.put(key, mergeBySums(key, parts, dofIndex)));

        List<String> removedDofs = new ArrayList<>();
        for (String root : prescribedRoots) {
            uf.parent.keySet().stream().filter(d -> uf.find(d).equals(root))
                    .sorted().forEach(removedDofs::add);
        }
        for (ConstraintRecord c : constraints) {
            if (c.kind() == ConstraintKind.TIED) {
                effects.add(new ConstraintEffect(c.constraintId(), "TIED", "ELIMINATION",
                        List.of(c.dofA(), c.dofB()), List.of(), null));
            }
        }
        if (!removedDofs.isEmpty()) {
            effects.add(new ConstraintEffect("prescribed-elimination", "PRESCRIBED",
                    "ELIMINATION", removedDofs, List.of(), null));
        }
        if (!removedEntries.isEmpty()) {
            diagnostics.add(Diagnostic.warn("ROWS_COLS_ELIMINATED",
                    removedEntries.size() + " matrix entries removed into the eliminated/RHS block",
                    List.copyOf(removedEntries).subList(0,
                            Math.min(8, removedEntries.size()))));
        }
        return out;
    }

    /** Merge already-summed entries: f64 fold is again done over the fixed part order. */
    private static AssembledEntry mergeBySums(String key, List<AssembledEntry> parts,
                                              Map<String, Integer> dofIndex) {
        String[] p = key.split("\\|\\|", 2);
        double f64 = 0.0;
        Q exact = Q.ZERO;
        List<String> cids = new ArrayList<>();
        for (AssembledEntry e : parts) {
            f64 += e.sumF64LeftToRight();
            exact = exact.add(Q.parse(e.sumRationalText()));
            cids.addAll(e.contributionIds());
        }
        cids.sort(String::compareTo);
        double rat = exact.toDouble();
        return new AssembledEntry("K[" + p[0] + "," + p[1] + "]",
                dofIndex.get(p[0]), dofIndex.get(p[1]), p[0], p[1], cids,
                f64, rat, exact.toString(), Math.abs(f64 - rat));
    }

    private static Map<String, AssembledEntry> applyPenalty(
            List<ConstraintRecord> constraints, Map<String, Integer> dofIndex,
            Map<String, AssembledEntry> freeEntries, UF uf,
            java.util.Set<String> prescribedRoots, List<ConstraintEffect> effects,
            List<Contribution> penaltyContributions, List<Diagnostic> diagnostics) {

        // Penalty keeps all DOFs (no redirection, no row removal). TIED terms alpha(ua-ub)^2,
        // PRESCRIBED terms alpha(u - ubar)^2 add diagonal/coupling entries.
        Map<String, List<Contribution>> stacks = new LinkedHashMap<>();
        for (AssembledEntry e : freeEntries.values()) {
            stacks.put(entryKey(e.rowGlobalDof(), e.colGlobalDof()), new ArrayList<>());
        }
        // We need raw contributions: caller passes penalty-only additions here, while free
        // entries are re-added through their stored rational/f64 sums as fixed pseudo terms.
        Map<String, double[]> pseudo = new LinkedHashMap<>(); // key -> {f64}
        Map<String, Q[]> pseudoQ = new LinkedHashMap<>();
        for (AssembledEntry e : freeEntries.values()) {
            String key = entryKey(e.rowGlobalDof(), e.colGlobalDof());
            pseudo.put(key, new double[]{e.sumF64LeftToRight()});
            pseudoQ.put(key, new Q[]{Q.parse(e.sumRationalText())});
        }

        List<String> penaltyEntryIds = new ArrayList<>();
        for (ConstraintRecord c : constraints) {
            if (c.penaltyAlphaRaw() == null || c.penaltyAlphaRaw().isBlank()) {
                continue;
            }
            Q alpha = Q.parse(c.penaltyAlphaRaw());
            if (c.kind() == ConstraintKind.TIED) {
                addPenaltyTerm(stacks, pseudo, pseudoQ, c, c.dofA(), c.dofA(), alpha,
                        dofIndex, penaltyContributions, penaltyEntryIds);
                addPenaltyTerm(stacks, c, c.dofB(), c.dofB(), alpha,
                        dofIndex, penaltyContributions, penaltyEntryIds);
                addPenaltyTerm(stacks, c, c.dofA(), c.dofB(), alpha.negate(),
                        dofIndex, penaltyContributions, penaltyEntryIds);
                addPenaltyTerm(stacks, c, c.dofB(), c.dofA(), alpha.negate(),
                        dofIndex, penaltyContributions, penaltyEntryIds);
                effects.add(new ConstraintEffect(c.constraintId(), "TIED", "PENALTY",
                        List.of(), List.of("K[" + c.dofA() + "," + c.dofA() + "]"),
                        c.penaltyAlphaRaw()));
            } else {
                addPenaltyTerm(stacks, c, c.dofA(), c.dofA(), alpha,
                        dofIndex, penaltyContributions, penaltyEntryIds);
                effects.add(new ConstraintEffect(c.constraintId(), "PRESCRIBED", "PENALTY",
                        List.of(), List.of("K[" + c.dofA() + "," + c.dofA() + "]"),
                        c.penaltyAlphaRaw()));
                diagnostics.add(Diagnostic.warn("PENALTY_RHS_NOTE",
                        "constraint " + c.constraintId()
                                + " also contributes alpha*ubar to RHS (matrix view shows K only)",
                        List.of(c.constraintId())));
            }
        }

        Map<String, AssembledEntry> out = new LinkedHashMap<>();
        // Re-sum free entries then overlay penalty pseudo terms deterministically.
        for (AssembledEntry e : freeEntries.values()) {
            String key = entryKey(e.rowGlobalDof(), e.colGlobalDof());
            List<Contribution> ps = stacks.getOrDefault(key, List.of());
            double f64 = e.sumF64LeftToRight();
            Q exact = Q.parse(e.sumRationalText());
            List<String> cids = new ArrayList<>(e.contributionIds());
            for (Contribution pc : ps) {
                f64 += Double.parseDouble(pc.rawValue());
                exact = exact.add(Q.parse(pc.rawValue()));
                cids.add(pc.contributionId());
            }
            double rat = exact.toDouble();
            out.put(key, new AssembledEntry(e.entryId(), e.rowIndex(), e.colIndex(),
                    e.rowGlobalDof(), e.colGlobalDof(), cids, f64, rat,
                    exact.toString(), Math.abs(f64 - rat)));
        }
        // Entries introduced solely by penalty.
        for (Contribution pc : penaltyContributions) {
            String key = entryKey(pc.rowGlobalDof(), pc.colGlobalDof());
            if (out.containsKey(key)) {
                continue;
            }
            double v = Double.parseDouble(pc.rawValue());
            Q q = Q.parse(pc.rawValue());
            out.put(key, new AssembledEntry("K[" + pc.rowGlobalDof() + ","
                    + pc.colGlobalDof() + "]", pc.rowIndex(), pc.colIndex(),
                    pc.rowGlobalDof(), pc.colGlobalDof(), List.of(pc.contributionId()),
                    v, q.toDouble(), q.toString(), 0.0));
        }
        return out;
    }

    private static void addPenaltyTerm(Map<String, List<Contribution>> stacks,
                                       ConstraintRecord c, String ra, String cb, Q alpha,
                                       Map<String, Integer> dofIndex,
                                       List<Contribution> out, List<String> entryIds) {
        String cid = "c:penalty:" + c.constraintId() + ":" + ra + "x" + cb;
        Contribution pc = new Contribution(cid, "PENALTY:" + c.constraintId(),
                c.constraintId(), c.constraintId(), ra, cb,
                dofIndex.get(ra), dofIndex.get(cb), alpha.toString(), "PENALTY");
        out.add(pc);
        stacks.computeIfAbsent(entryKey(ra, cb), k -> new ArrayList<>()).add(pc);
        entryIds.add("K[" + ra + "," + cb + "]");
    }
