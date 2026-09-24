package com.gsb.fea.core;

import com.gsb.fea.model.Constraint;
import com.gsb.fea.model.DofMap;
import com.gsb.fea.model.Element;
import com.gsb.fea.model.Node;
import com.gsb.fea.model.RawValue;
import com.gsb.fea.model.Renumbering;
import com.gsb.fea.model.SourceBundle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Global sparse assembler with full entry provenance.
 *
 * Determinism contract:
 *  - contributions to every global entry are summed in one fixed order
 *    (element import order, then local row, then local column);
 *  - elimination and penalty are separate strategies, never mixed;
 *  - repeated dofs, degenerate elements, isolated dofs, unknown mappings,
 *    dimension mismatches and asymmetric local matrices are diagnosed;
 *  - conflicting boundary decisions produce all legal candidates;
 *  - renumbering only rewrites display indices.
 */
public final class Assembler {
    private Assembler() {}

    public static AssemblyReport assemble(SourceBundle bundle, AssembleOptions options) {
        List<Diagnostic> diags = new ArrayList<>();
        Map<String, Element> elById = new LinkedHashMap<>();
        for (Element e : bundle.elements()) elById.put(e.elementId(), e);
        Map<String, DofMap> mapByEl = new LinkedHashMap<>();
        for (DofMap m : bundle.maps()) mapByEl.put(m.elementId(), m);
        Map<String, Node> nodeById = new LinkedHashMap<>();
        for (Node n : bundle.nodes()) nodeById.put(n.nodeId(), n);

        List<String> dofOrder = new ArrayList<>(bundle.allDofIds());

        checkRawValues(bundle, diags);
        checkIsolatedAndCoverage(bundle, diags);
        checkDegenerate(bundle, nodeById, diags);

        // gather raw contributions
        List<Contribution> canonical = new ArrayList<>();
        int ordinal = 0;
        for (Element e : bundle.elements()) {
            DofMap map = mapByEl.get(e.elementId());
            if (map == null) {
                diags.add(Diagnostic.of("MAP_MISSING", Diagnostic.Severity.ERROR,
                        "element has no global dof mapping: " + e.elementId(),
                        List.of(Ids.element(e.elementId()))));
                continue;
            }
            if (map.mapping().size() != e.size()) {
                diags.add(Diagnostic.of("MAP_DIM_MISMATCH", Diagnostic.Severity.ERROR,
                        "mapping size " + map.mapping().size() + " != local dofs " + e.size()
                                + " on element " + e.elementId(),
                        List.of(Ids.element(e.elementId()), Ids.map(e.elementId()))));
                continue;
            }
            if (checkDuplicateDofs(e, map, diags)) continue;
            ordinal = collectContributions(e, map, canonical, ordinal, diags);
        }

        checkAsymmetry(bundle, diags);
        List<Contribution> ordered = orderContributions(canonical, options.floatOrder());
        Map<String, List<Contribution>> byEntry = group(ordered);
        List<FloatSensitivity> sensitivity =
                options.floatOrder() == AssembleOptions.FloatOrder.CANONICAL
                        ? sensitivity(canonical) : List.of();

        if (options.strategy() == AssembleOptions.Strategy.FREE) {
            AssemblyCandidate c = buildCandidate("free", "free assembly",
                    Map.of(), Map.of(), byEntry, bundle.renumbering(), List.of());
            return new AssemblyReport(options.strategy(), options.floatOrder(), dofOrder,
                    List.of(c), diags, sensitivity, false);
        }
        return constrain(bundle, options, dofOrder, byEntry, diags, sensitivity);
    }

    private static void checkRawValues(SourceBundle b, List<Diagnostic> out) {
        for (Element e : b.elements()) {
            List<String> bad = new ArrayList<>();
            for (int a = 0; a < e.matrix().size(); a++) {
                List<RawValue> row = e.matrix().get(a);
                for (int k = 0; k < row.size(); k++) {
                    RawValue rv = row.get(k);
                    if (!rv.parseOk()) bad.add(Ids.term(e.elementId(), a, k));
                }
            }
            if (!bad.isEmpty()) {
                out.add(new Diagnostic("UNCONVERTIBLE_VALUE", Diagnostic.Severity.ERROR,
                        "local matrix value cannot be normalized; kept at source: "
                                + e.elementId(), bad, Map.of("elementId", e.elementId())));
            }
        }
        for (Constraint c : b.constraints()) {
            if (c.value() != null && !c.value().parseOk()) {
                out.add(new Diagnostic("UNCONVERTIBLE_VALUE", Diagnostic.Severity.ERROR,
                        "constraint value cannot be normalized; kept at source",
                        List.of(Ids.constraint(c.constraintId())),
                        Map.of("constraintId", c.constraintId(), "token", c.value().text())));
            }
        }
    }

    private static void checkIsolatedAndCoverage(SourceBundle b, List<Diagnostic> out) {
        Set<String> touched = new LinkedHashSet<>();
        Set<String> known = new LinkedHashSet<>(b.allDofIds());
        for (DofMap m : b.maps()) {
            for (String g : m.mapping()) {
                touched.add(g);
                if (!known.contains(g)) {
                    out.add(Diagnostic.of("UNKNOWN_GLOBAL_DOF", Diagnostic.Severity.ERROR,
                            "mapping references dof not declared at any node: " + g
                                    + " (element " + m.elementId() + ")",
                            List.of(Ids.map(m.elementId()), Ids.dof(g))));
                }
            }
        }
        List<String> isolated = new ArrayList<>();
        for (String d : b.allDofIds()) if (!touched.contains(d)) isolated.add(Ids.dof(d));
        if (!isolated.isEmpty()) {
            out.add(new Diagnostic("ISOLATED_DOF", Diagnostic.Severity.WARNING,
                    "degrees of freedom never referenced by any element mapping",
                    isolated, Map.of("count", Integer.toString(isolated.size()))));
        }
    }

    private static void checkDegenerate(SourceBundle b, Map<String, Node> nodes,
                                        List<Diagnostic> out) {
        for (Element e : b.elements()) {
            List<String> repeatedNodes = repeated(e.nodeIds());
            if (!repeatedNodes.isEmpty()) {
                out.add(new Diagnostic("DEGENERATE_ELEMENT", Diagnostic.Severity.WARNING,
                        "element references the same node more than once",
                        List.of(Ids.element(e.elementId())),
                        Map.of("elementId", e.elementId(), "nodes", String.join(",", repeatedNodes))));
            }
            for (String n : e.nodeIds()) {
                if (!nodes.containsKey(n)) {
                    out.add(Diagnostic.of("UNKNOWN_NODE", Diagnostic.Severity.ERROR,
                            "element references missing node " + n,
                            List.of(Ids.element(e.elementId()), Ids.node(n))));
                }
            }
            if (e.matrix().size() != e.size()) {
                out.add(Diagnostic.of("MATRIX_DIM_MISMATCH", Diagnostic.Severity.ERROR,
                        "local matrix " + e.matrix().size() + " rows vs " + e.size()
                                + " local dofs", List.of(Ids.element(e.elementId()))));
            }
        }
    }

    private static boolean checkDuplicateDofs(Element e, DofMap map, List<Diagnostic> out) {
        List<String> repeated = repeated(map.mapping());
        if (!repeated.isEmpty()) {
            out.add(new Diagnostic("DUPLICATE_DOF", Diagnostic.Severity.ERROR,
                    "one element maps multiple local dofs onto global dof "
                            + String.join(",", repeated),
                    List.of(Ids.element(e.elementId()), Ids.map(e.elementId())),
                    Map.of("elementId", e.elementId(), "dofs", String.join(",", repeated))));
            return true;
        }
        return false;
    }

    private static int collectContributions(Element e, DofMap map,
                                            List<Contribution> sink, int startOrdinal,
                                            List<Diagnostic> diags) {
        int ordinal = startOrdinal;
        for (int a = 0; a < e.size(); a++) {
            if (a >= e.matrix().size()) continue;
            List<RawValue> row = e.matrix().get(a);
            for (int k = 0; k < Math.min(row.size(), e.size()); k++) {
                RawValue rv = row.get(k);
                if (!rv.parseOk()) continue; // diagnosed at raw stage; left in place
                String gRow = map.mapping().get(a);
                String gCol = map.mapping().get(k);
                String entryUrn = Ids.entry(gRow, gCol);
                sink.add(new Contribution(Ids.contribution(e.elementId(), a, k), entryUrn,
                        e.elementId(), a, k, gRow, gCol, rv.numeric(), rv.text(),
                        e.matrixUnit() == null ? rv.unit() : e.matrixUnit(), ordinal++));
            }
        }
        return ordinal;
    }

    private static void checkAsymmetry(SourceBundle b, List<Diagnostic> out) {
        for (Element e : b.elements()) {
            int n = e.size();
            if (e.matrix().size() != n) continue;
            double max = 0.0;
            String where = null;
            for (int a = 0; a < n; a++) {
                if (e.matrix().get(a).size() != n) continue;
                for (int k = a + 1; k < n; k++) {
                    RawValue x = e.matrix().get(a).get(k);
                    RawValue y = e.matrix().get(k).get(a);
                    if (!x.parseOk() || !y.parseOk()) continue;
                    double d = Math.abs(x.numeric() - y.numeric());
                    if (d > max) { max = d; where = a + "," + k; }
                }
            }
            if (max > 0.0) {
                out.add(new Diagnostic("ASYMMETRIC_LOCAL_MATRIX", Diagnostic.Severity.WARNING,
                        "local matrix is not symmetric (max |K[a][b]-K[b][a]| = " + max + ")",
                        List.of(Ids.element(e.elementId())),
                        Map.of("elementId", e.elementId(), "maxAbsDiff",
                                Double.toHexString(max), "firstAt", where == null ? "" : where)));
            }
        }
    }

    private static List<String> repeated(List<String> values) {
        List<String> seen = new ArrayList<>();
        List<String> rep = new ArrayList<>();
        for (String v : values) if (!seen.contains(v)) seen.add(v);
        for (String v : seen) {
            int c = 0;
            for (String x : values) if (x.equals(v)) c++;
            if (c > 1) rep.add(v);
        }
        return rep;
    }

    private static final Comparator<Contribution> CANON_ORDER =
            Comparator.comparingInt(Contribution::ordinal);

    private static final Comparator<Contribution> GROUPED_ORDER = Comparator
            .comparing((Contribution c) -> c.elementId())
            .thenComparingInt(Contribution::localCol)
            .thenComparingInt(Contribution::localRow);

    private static List<Contribution> orderContributions(List<Contribution> all,
                                                          AssembleOptions.FloatOrder mode) {
        List<Contribution> out = new ArrayList<>(all);
        out.sort(mode == AssembleOptions.FloatOrder.GROUPED ? GROUPED_ORDER : CANON_ORDER);
        return out;
    }

    private static Map<String, List<Contribution>> group(List<Contribution> ordered) {
        Map<String, List<Contribution>> by = new TreeMap<>();
        for (Contribution c : ordered) by.computeIfAbsent(c.sparseEntryUrn(), k -> new ArrayList<>()).add(c);
        return by;
    }

    private static double sumInOrder(List<Contribution> cs) {
        double acc = 0.0;
        for (Contribution c : cs) acc += c.value();
        return acc;
    }

    private static List<FloatSensitivity> sensitivity(List<Contribution> all) {
        List<FloatSensitivity> out = new ArrayList<>();
        Map<String, List<Contribution>> canon = group(orderContributions(all, AssembleOptions.FloatOrder.CANONICAL));
        Map<String, List<Contribution>> grp = group(orderContributions(all, AssembleOptions.FloatOrder.GROUPED));
        for (Map.Entry<String, List<Contribution>> en : canon.entrySet()) {
            double a = sumInOrder(en.getValue());
            List<Contribution> other = grp.get(en.getKey());
            if (other == null) continue;
            double b = sumInOrder(other);
            if (a != b) {
                out.add(new FloatSensitivity(en.getKey(), a, b, Math.abs(a - b),
                        java.math.BigDecimal.valueOf(a).compareTo(java.math.BigDecimal.valueOf(b)) == 0
                                ? 0 : ulpsBetween(a, b)));
            }
        }
        return out;
    }

    private static long ulpsBetween(double a, double b) {
        long la = Double.doubleToRawLongBits(a < 0 ? -0.0 : a) ^ (a < 0 ? 0x8000000000000000L : 0);
        long lb = Double.doubleToRawLongBits(b < 0 ? -0.0 : b) ^ (b < 0 ? 0x8000000000000000L : 0);
        return Math.abs(la - lb);
    }

    private static AssemblyCandidate buildCandidate(String id, String label,
                                                    Map<String, Double> prescribed,
                                                    Map<String, Double> penalty,
                                                    Map<String, List<Contribution>> by,
                                                    Renumbering rn,
                                                    List<Diagnostic> extra) {
        List<SparseEntry> entries = new ArrayList<>();
        for (Map.Entry<String, List<Contribution>> en : by.entrySet()) {
            List<Contribution> cs = en.getValue();
            Contribution first = cs.get(0);
            String row = first.rowDof();
            String col = first.colDof();
            int ri = indexOf(row, rn);
            int ci = indexOf(col, rn);
            entries.add(new SparseEntry(en.getKey(), row, col, ri, ci, sumInOrder(cs), cs));
        }
        entries.sort(Comparator.comparingInt(SparseEntry::rowIndex)
                .thenComparingInt(SparseEntry::colIndex));
        List<Diagnostic> all = new ArrayList<>(extra);
        return new AssemblyCandidate(id, label, prescribed, penalty, entries, all, null);
    }

    private static int indexOf(String dofId, Renumbering rn) {
        if (rn != null && rn.dofToIndex().containsKey(dofId)) return rn.dofToIndex().get(dofId);
        return -1;
    }

    private static AssemblyReport constrain(SourceBundle bundle, AssembleOptions options,
                                            List<String> dofOrder,
                                            Map<String, List<Contribution>> free,
                                            List<Diagnostic> diags,
                                            List<FloatSensitivity> sensitivity) {
        List<Constraint> usable = new ArrayList<>();
        boolean hasFix = false, hasPenalty = false;
        for (Constraint c : bundle.constraints()) {
            boolean parseOk = c.value() == null || c.value().parseOk();
            if (!parseOk) {
                diags.add(Diagnostic.of("CONSTRAINT_SKIPPED_UNCONVERTIBLE",
                        Diagnostic.Severity.WARNING,
                        "constraint kept at source but not applied (unconvertible value)",
                        List.of(Ids.constraint(c.constraintId()))));
                continue;
            }
            usable.add(c);
            hasFix |= Constraint.FIX.equals(c.kind());
            hasPenalty |= Constraint.PENALTY.equals(c.kind());
        }
        if (hasFix && hasPenalty) {
            Diagnostic mix = Diagnostic.of("MIXED_CONSTRAINT_STRATEGY",
                    Diagnostic.Severity.ERROR,
                    "elimination (FIX) and PENALTY constraints must not be mixed in one assembly",
                    usable.stream().map(c -> Ids.constraint(c.constraintId())).toList());
            diags.add(mix);
            return new AssemblyReport(options.strategy(), options.floatOrder(), dofOrder,
                    List.of(), diags, sensitivity, true);
        }

        // group usable constraints per dof; conflicts -> all legal candidates
        Map<String, List<Constraint>> perDof = new LinkedHashMap<>();
        for (Constraint c : usable) perDof.computeIfAbsent(c.dofId(), k -> new ArrayList<>()).add(c);
        List<Map<String, Constraint>> choices = new ArrayList<>();
        choices.add(new LinkedHashMap<>());
        for (Map.Entry<String, List<Constraint>> en : perDof.entrySet()) {
            List<Constraint> uniq = new ArrayList<>();
            for (Constraint c : en.getValue()) {
                boolean dup = false;
                for (Constraint u : uniq) {
                    if (sameDecision(u, c)) dup = true;
                }
                if (!dup) uniq.add(c);
            }
            if (uniq.size() > 1) {
                List<Map<String, Constraint>> grown = new ArrayList<>();
                for (Map<String, Constraint> base : choices) {
                    for (Constraint pick : uniq) {
                        Map<String, Constraint> next = new LinkedHashMap<>(base);
                        next.put(en.getKey(), pick);
                        grown.add(next);
                    }
                }
                choices = grown;
            } else {
                for (Map<String, Constraint> ch : choices) ch.put(en.getKey(), uniq.get(0));
            }
        }

        List<AssemblyCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < choices.size(); i++) {
            Map<String, Constraint> chosen = choices.get(i);
            if (options.strategy() == AssembleOptions.Strategy.ELIMINATION) {
                candidates.add(eliminate(chosen, i, free, bundle.renumbering()));
            } else {
                candidates.add(penalize(chosen, i, free, bundle.renumbering()));
            }
        }
        if (candidates.size() > 1) {
            diags.add(new Diagnostic("INDETERMINATE_CONSTRAINT", Diagnostic.Severity.WARNING,
                    "constraints conflict; all " + candidates.size()
                            + " legal candidates retained, none auto-selected",
                    candidates.stream().map(AssemblyCandidate::candidateId).toList(),
                    Map.of("candidateCount", Integer.toString(candidates.size()))));
        }
        return new AssemblyReport(options.strategy(), options.floatOrder(), dofOrder,
                candidates, diags, sensitivity, false);
    }

    private static boolean sameDecision(Constraint a, Constraint b) {
        if (!a.kind().equals(b.kind())) return false;
        if (a.value() == null || b.value() == null) return a.value() == b.value();
        return a.value().parseOk() && b.value().parseOk()
                && Double.compare(a.value().numeric(), b.value().numeric()) == 0;
    }

    private static AssemblyCandidate eliminate(Map<String, Constraint> chosen, int idx,
                                               Map<String, List<Contribution>> free,
                                               Renumbering rn) {
        Set<String> fixed = chosen.keySet();
        Map<String, Double> prescribed = new LinkedHashMap<>();
        for (Constraint c : chosen.values()) prescribed.put(c.dofId(), c.value().numeric());
        Map<String, List<Contribution>> kept = new TreeMap<>();
        List<Diagnostic> diag = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (Map.Entry<String, List<Contribution>> en : free.entrySet()) {
            Contribution c0 = en.getValue().get(0);
            if (fixed.contains(c0.rowDof()) || fixed.contains(c0.colDof())) {
                removed.add(en.getKey());
                continue;
            }
            kept.put(en.getKey(), en.getValue());
        }
        if (!removed.isEmpty()) {
            diag.add(new Diagnostic("CONSTRAINT_ELIMINATED", Diagnostic.Severity.INFO,
                    "rows/columns touching fixed dofs removed by elimination", removed,
                    Map.of("fixedDofs", String.join(",", fixed))));
        }
        String id = "elim-" + (idx + 1);
        return buildCandidate(id, "elimination candidate " + (idx + 1), prescribed, Map.of(),
                kept, rn, diag).withReason(reason(chosen));
    }

    private static AssemblyCandidate penalize(Map<String, Constraint> chosen, int idx,
                                              Map<String, List<Contribution>> free,
                                              Renumbering rn) {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (Constraint c : chosen.values()) weights.put(c.dofId(), c.value().numeric());
        Map<String, List<Contribution>> augmented = new TreeMap<>(free);
        List<String> touched = new ArrayList<>();
        for (Map.Entry<String, Double> en : weights.entrySet()) {
            String dofId = en.getKey();
            String urn = Ids.entry(dofId, dofId);
            List<Contribution> cs = new ArrayList<>(
                    augmented.getOrDefault(urn, new ArrayList<>()));
            int ord = cs.isEmpty() ? 0 : cs.get(cs.size() - 1).ordinal() + 1;
            Constraint src = chosen.get(dofId);
            cs.add(new Contribution("pen:" + src.constraintId(), urn,
                    "#penalty:" + src.constraintId(), -1, -1, dofId, dofId,
                    en.getValue(), src.value().text(), src.value().unit(), ord));
            augmented.put(urn, cs);
            touched.add(urn);
        }
        List<Diagnostic> diag = new ArrayList<>();
        diag.add(new Diagnostic("CONSTRAINT_PENALIZED", Diagnostic.Severity.INFO,
                "diagonal penalty contributions added (no rows/columns removed)", touched,
                Map.of("penaltyDofs", String.join(",", weights.keySet()))));
        String id = "pen-" + (idx + 1);
        return buildCandidate(id, "penalty candidate " + (idx + 1), Map.of(), weights,
                augmented, rn, diag).withReason(reason(chosen));
    }

    private static String reason(Map<String, Constraint> chosen) {
        StringBuilder sb = new StringBuilder();
        for (Constraint c : chosen.values()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(c.constraintId()).append(' ').append(c.kind()).append('=')
              .append(c.value() == null ? "?" : c.value().text());
        }
        return sb.toString();
    }
}
