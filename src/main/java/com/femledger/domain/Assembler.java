package com.femledger.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Assembles the global sparse matrix from a Model snapshot and records the
 * provenance of every nonzero entry.
 *
 * Semantics enforced here:
 *  - duplicate contributions are summed in a deterministic canonical order
 *    (element original id, local row, local col);
 *  - elimination and penalty constraint strategies are never mixed in one run;
 *  - isolated dofs and asymmetric contributions are explicit diagnostics;
 *  - renumbering only changes matrix indices, never physical correspondence
 *    (entries always carry their stable global dof ids);
 *  - values whose units cannot be converted stay in place, raw, with a
 *    diagnostic, and are excluded from the normalized sum.
 */
public final class Assembler {

    public static final double PENALTY = 1.0e20;
    /** Known conversions into the normalized stiffness unit "N/m". */
    private static final Map<String, Double> TO_N_PER_M = Map.of(
            "N/m", 1.0,
            "N/mm", 1000.0,
            "lbf/in", 175.1268352463791);

    public record Contribution(
            String elementId, int localRow, int localCol,
            String dofI, String dofJ,
            double rawValue, String rawUnit,
            Double normValue, String normUnit,   // null when unconvertible
            int canonicalOrder) {}

    public record Entry(int row, int col, String dofI, String dofJ,
                        double value, List<Contribution> contribs) {
        public String stableId() { return dofI + "|" + dofJ; }
    }

    public record Diag(String code, String severity, String message, List<String> objects) {}

    public record AssemblyResult(
            List<Entry> entries,
            List<Diag> diags,
            Map<String, Integer> numbering,
            Map<String, Double> imposed,      // dof -> imposed value (eliminated)
            List<String> penalized,           // dofs with penalty applied
            List<List<String>> constraintCandidates, // conflicting candidate groups kept, none applied
            boolean sumOrderSensitive,
            double sumOrderDelta) {}

    private record Key(String a, String b) {}

    public static AssemblyResult assemble(Model model) {
        List<Diag> diags = new ArrayList<>();

        // ---- degenerate elements ------------------------------------------------
        List<Model.ElementRec> active = new ArrayList<>();
        for (Model.ElementRec e : model.elements()) {
            long distinct = e.conn().stream().distinct().count();
            if (distinct < e.conn().size() || distinct < 2) {
                diags.add(new Diag("DEGENERATE_ELEMENT", "ERROR",
                        "element " + e.origId + " has repeated or collapsed connectivity "
                                + e.conn() + "; excluded from assembly",
                        List.of(e.origId)));
            } else {
                active.add(e);
            }
        }

        // ---- asymmetric local matrices ------------------------------------------
        for (Model.ElementRec e : active) {
            int n = e.matrix().size();
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    double a = e.matrix().get(i).get(j);
                    double b = e.matrix().get(j).get(i);
                    if (Double.compare(a, b) != 0) {
                        diags.add(new Diag("ASYMMETRIC_LOCAL", "WARNING",
                                "local matrix of " + e.origId + " is asymmetric at (" + i + "," + j
                                        + "): " + a + " != " + b + "; assembled as-is",
                                List.of(e.origId)));
                        i = n; // one diagnostic per element is enough
                        break;
                    }
                }
            }
        }

        // ---- collect contributions ----------------------------------------------
        Map<Key, List<Contribution>> buckets = new TreeMap<>(
                Comparator.comparing((Key k) -> k.a).thenComparing(k -> k.b));
        Map<String, Integer> dofUse = new TreeMap<>();
        for (Model.ElementRec e : active) {
            Map<String, Integer> perElement = new LinkedHashMap<>();
            int n = e.localDofs().size();
            for (int i = 0; i < n; i++) {
                String gI = model.dofMap().get(e.origId() + "." + e.localDofs().get(i));
                if (gI == null) {
                    diags.add(new Diag("UNMAPPED_DOF", "ERROR",
                            "no global dof mapping for " + e.origId() + "." + e.localDofs().get(i),
                            List.of(e.origId(), e.localDofs().get(i))));
                    continue;
                }
                perElement.merge(gI, 1, Integer::sum);
                for (int j = 0; j < n; j++) {
                    String gJ = model.dofMap().get(e.origId() + "." + e.localDofs().get(j));
                    if (gJ == null) continue;
                    double raw = e.matrix().get(i).get(j);
                    Double factor = TO_N_PER_M.get(e.unit());
                    Double norm = factor == null ? null : raw * factor;
                    buckets.computeIfAbsent(new Key(gI, gJ), k -> new ArrayList<>())
                            .add(new Contribution(e.origId(), i, j, gI, gJ,
                                    raw, e.unit(), norm, norm == null ? null : "N/m", -1));
                    dofUse.merge(gI, 1, Integer::sum);
                    dofUse.merge(gJ, 1, Integer::sum);
                }
            }
            perElement.forEach((gid, count) -> {
                if (count > 1) {
                    diags.add(new Diag("DUP_DOF", "WARNING",
                            "element " + e.origId + " maps " + count
                                    + " local dofs to global dof " + gid
                                    + "; duplicate contributions summed in canonical order",
                            List.of(e.origId(), gid)));
                }
            });
        }
        for (Model.ElementRec e : active) {
            if (!TO_N_PER_M.containsKey(e.unit())) {
                diags.add(new Diag("UNIT_UNCONVERTED", "WARNING",
                        "unit '" + e.unit() + "' of element " + e.origId()
                                + " has no conversion to N/m; raw values kept in place, "
                                + "excluded from normalized sum",
                        List.of(e.origId(), e.unit())));
            }
        }

        // ---- canonical-order summation + order sensitivity ----------------------
        boolean sensitive = false;
        double maxDelta = 0.0;
        Map<Key, Double> summed = new TreeMap<>(Comparator.comparing((Key k) -> k.a).thenComparing(k -> k.b));
        Map<Key, List<Contribution>> ordered = new TreeMap<>(Comparator.comparing((Key k) -> k.a).thenComparing(k -> k.b));
        for (var bucket : buckets.entrySet()) {
            List<Contribution> cs = new ArrayList<>(bucket.getValue());
            cs.sort(Comparator.comparing(Contribution::elementId)
                    .thenComparingInt(Contribution::localRow)
                    .thenComparingInt(Contribution::localCol));
            for (int k = 0; k < cs.size(); k++) {
                Contribution c = cs.get(k);
                cs.set(k, new Contribution(c.elementId(), c.localRow(), c.localCol(),
                        c.dofI(), c.dofJ(), c.rawValue(), c.rawUnit(),
                        c.normValue(), c.normUnit(), k));
            }
            ordered.put(bucket.getKey(), cs);
            double fwd = 0.0;
            for (Contribution c : cs) fwd += c.normValue() == null ? 0.0 : c.normValue();
            double rev = 0.0;
            for (int k = cs.size() - 1; k >= 0; k--) {
                Double nv = cs.get(k).normValue();
                rev += nv == null ? 0.0 : nv;
            }
            summed.put(bucket.getKey(), fwd);
            double delta = Math.abs(fwd - rev);
            if (Double.compare(fwd, rev) != 0) {
                sensitive = true;
                maxDelta = Math.max(maxDelta, delta);
            }
        }
        if (sensitive) {
            diags.add(new Diag("SUM_ORDER_SENSITIVE", "INFO",
                    "floating-point sum depends on contribution order (max delta "
                            + maxDelta + "); canonical order " + model.summation()
                            + " makes the result reproducible under " + model.floatMode(),
                    List.of(model.floatMode(), model.summation())));
        }

        // ---- constraints ----------------------------------------------------------
        Map<String, Double> imposed = new LinkedHashMap<>();
        List<String> penalized = new ArrayList<>();
        List<List<String>> candidates = new ArrayList<>();
        Model.ConstraintSet cs = model.constraints();
        if (cs != null && !cs.items().isEmpty()) {
            if (!cs.strategy().equals(model.strategy())) {
                diags.add(new Diag("MIXED_STRATEGY", "ERROR",
                        "constraint set " + cs.version() + " uses " + cs.strategy()
                                + " but assembly strategy is " + model.strategy()
                                + "; elimination and penalty are never mixed, "
                                + "constraint items NOT applied",
                        List.of(cs.version(), cs.strategy(), model.strategy())));
            } else {
                Map<String, List<Model.ConstraintItem>> byDof = new LinkedHashMap<>();
                for (Model.ConstraintItem item : cs.items()) {
                    byDof.computeIfAbsent(item.dof(), k -> new ArrayList<>()).add(item);
                }
                for (var group : byDof.entrySet()) {
                    List<Model.ConstraintItem> items = group.getValue();
                    long distinct = items.stream().map(Model.ConstraintItem::value).distinct().count();
                    if (distinct > 1) {
                        List<String> opts = items.stream()
                                .map(it -> it.value() + " " + it.unit()).toList();
                        candidates.add(opts);
                        diags.add(new Diag("CONSTRAINT_CONFLICT", "ERROR",
                                "dof " + group.getKey() + " constrained " + items.size()
                                        + " times with different values " + opts
                                        + " in " + cs.version()
                                        + "; no unique decision, all candidates kept, dof left unconstrained",
                                List.of(group.getKey(), cs.version())));
                        continue;
                    }
                    Model.ConstraintItem item = items.get(0);
                    if ("ELIMINATE".equals(cs.strategy())) {
                        imposed.put(group.getKey(), item.value());
                    } else {
                        penalized.add(group.getKey());
                        imposed.put(group.getKey(), item.value());
                    }
                }
            }
        }

        // ---- apply constraints to entries -----------------------------------------
        List<Entry> entries = new ArrayList<>();
        for (var bucket : ordered.entrySet()) {
            String gI = bucket.getKey().a, gJ = bucket.getKey().b;
            if (imposed.containsKey(gI) || imposed.containsKey(gJ)) {
                if ("ELIMINATE".equals(model.strategy())) continue; // row/col eliminated
            }
            double value = summed.get(bucket.getKey());
            if ("PENALTY".equals(model.strategy()) && gI.equals(gJ) && penalized.contains(gI)) {
                value += PENALTY;
            }
            entries.add(new Entry(-1, -1, gI, gJ, value, bucket.getValue()));
        }

        // ---- asymmetric global pattern --------------------------------------------
        Map<String, Double> byPair = new TreeMap<>();
        for (Entry e : entries) byPair.put(e.stableId(), e.value());
        for (Entry e : entries) {
            if (e.dofI().equals(e.dofJ())) continue;
            Double transpose = byPair.get(e.dofJ() + "|" + e.dofI());
            if (transpose == null) {
                diags.add(new Diag("ASYMMETRIC_ENTRY", "WARNING",
                        "entry (" + e.dofI() + "," + e.dofJ() + ") has no transpose counterpart",
                        List.of(e.dofI(), e.dofJ())));
            }
        }

        // ---- isolated dofs ----------------------------------------------------------
        Map<String, Integer> numbering = model.numbering() != null
                ? new TreeMap<>(model.numbering()) : defaultNumbering(model);
        for (String gid : numbering.keySet()) {
            boolean touched = entries.stream().anyMatch(
                    e -> e.dofI().equals(gid) || e.dofJ().equals(gid));
            if (!touched && !imposed.containsKey(gid)) {
                diags.add(new Diag("ISOLATED_DOF", "WARNING",
                        "global dof " + gid + " receives no contribution and is not constrained",
                        List.of(gid)));
            }
        }

        // ---- assign matrix indices (renumbering touches only this) -----------------
        List<Entry> indexed = new ArrayList<>();
        for (Entry e : entries) {
            Integer r = numbering.get(e.dofI());
            Integer c = numbering.get(e.dofJ());
            if (r == null || c == null) continue;
            indexed.add(new Entry(r, c, e.dofI(), e.dofJ(), e.value(), e.contribs()));
        }
        indexed.sort(Comparator.comparingInt(Entry::row).thenComparingInt(Entry::col));

        return new AssemblyResult(indexed, diags, numbering, imposed, penalized,
                candidates, sensitive, maxDelta);
    }

    private static Map<String, Integer> defaultNumbering(Model model) {
        Map<String, Integer> numbering = new TreeMap<>();
        List<String> gids = model.dofMap().values().stream().distinct().sorted().toList();
        for (int i = 0; i < gids.size(); i++) numbering.put(gids.get(i), i);
        return numbering;
    }
}
