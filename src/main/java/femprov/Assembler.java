package femprov;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sparse assembly engine.
 * Semantics held here:
 *  - duplicate contributions are summed in a deterministic order
 *    (sorted by element id, then local i, then local j), under the
 *    imported float mode (SEQ left-fold or PAIRWISE);
 *  - constraint elimination and penalty strategies are never mixed in
 *    one result: a mixed constraint set yields a diagnostic plus both
 *    candidate results, all retained;
 *  - isolated dofs and non-symmetric contributions are explicit
 *    diagnostics, never silently dropped;
 *  - renumbering only changes integer indices, never the physical
 *    dof identity attached to an entry.
 */
public final class Assembler {

    public record Contribution(String elementId, int localI, int localJ, double value) {}

    /** One global sparse entry with its full provenance stack. */
    public record Entry(String rowDof, String colDof, int row, int col,
                        double value, List<Contribution> contribs) {
        public String id() { return "nz(" + rowDof + "," + colDof + ")"; }
    }

    public record Diag(String kind, String summary, List<String> objects) {}

    public record Result(String strategy, List<Entry> entries, List<Diag> diags,
                         List<Result> candidates) {
        public Entry find(String rowDof, String colDof) {
            for (Entry e : entries) if (e.rowDof.equals(rowDof) && e.colDof.equals(colDof)) return e;
            return null;
        }
    }

    private record NormElem(String id, List<String> map, double[][] k) {}
    private record Tagged(String rowDof, String colDof, Contribution c) {}

    /** Assemble from a raw import. forcedStrategy picks one strategy when the
     *  constraint set mixes elimination and penalty; null keeps both candidates. */
    public static Result assemble(Model.RawImport raw, String forcedStrategy,
                                  Map<String, Integer> renumber) {
        List<Diag> diags = new ArrayList<>();
        List<NormElem> elems = new ArrayList<>();
        for (Model.RawElement re : raw.elements()) {
            Double f = Model.unitFactor(re.unit());
            if (f == null) {
                diags.add(new Diag("UNIT_UNCONVERTIBLE",
                        "unit '" + re.unit() + "' of element " + re.origId()
                                + " has no SI conversion; raw values kept in place, element excluded from normalized assembly",
                        List.of(re.origId())));
                continue;
            }
            List<String> map = raw.dofMaps().get(re.origId());
            if (map == null) {
                diags.add(new Diag("MISSING_DOF_MAP", "element " + re.origId() + " has no dof map", List.of(re.origId())));
                continue;
            }
            if (re.connectivity().size() != re.connectivity().stream().distinct().count())
                diags.add(new Diag("DEGENERATE_ELEMENT",
                        "element " + re.origId() + " repeats a node in connectivity " + re.connectivity(),
                        List.of(re.origId())));
            if (map.size() != map.stream().distinct().count())
                diags.add(new Diag("DUPLICATE_DOF_IN_ELEMENT",
                        "element " + re.origId() + " maps two local dofs to the same global dof " + map,
                        List.of(re.origId())));
            double[][] k = new double[re.matrix().size()][];
            for (int i = 0; i < re.matrix().size(); i++) {
                k[i] = new double[re.matrix().get(i).size()];
                for (int j = 0; j < k[i].length; j++) k[i][j] = Double.parseDouble(re.matrix().get(i).get(j)) * f;
            }
            for (int i = 0; i < k.length; i++)
                for (int j = i + 1; j < k[i].length; j++)
                    if (Double.compare(k[i][j], k[j][i]) != 0)
                        diags.add(new Diag("NON_SYMMETRIC",
                                "element " + re.origId() + " local k[" + i + "][" + j + "]=" + k[i][j]
                                        + " != k[" + j + "][" + i + "]=" + k[j][i],
                                List.of(re.origId())));
            elems.add(new NormElem(re.origId(), map, k));
        }

        // contributions in deterministic order: element id, local i, local j
        List<Tagged> all = new ArrayList<>();
        for (NormElem e : elems)
            for (int i = 0; i < e.k().length; i++)
                for (int j = 0; j < e.k()[i].length; j++)
                    all.add(new Tagged(e.map().get(i), e.map().get(j),
                            new Contribution(e.id(), i, j, e.k()[i][j])));
        all.sort((a, b) -> {
            int c = a.c().elementId().compareTo(b.c().elementId());
            if (c != 0) return c;
            c = Integer.compare(a.c().localI(), b.c().localI());
            return c != 0 ? c : Integer.compare(a.c().localJ(), b.c().localJ());
        });
        Map<String, List<Tagged>> byKey = new TreeMap<>();
        for (Tagged t : all)
            byKey.computeIfAbsent(t.rowDof() + "|" + t.colDof(), x -> new ArrayList<>()).add(t);

        // dof universe and indices; renumbering permutes indices only
        List<String> universe = new ArrayList<>(raw.declaredDofs());
        for (NormElem e : elems) for (String d : e.map()) if (!universe.contains(d)) universe.add(d);
        for (Model.RawConstraint rc : raw.constraints()) if (!universe.contains(rc.dof())) universe.add(rc.dof());
        universe.sort(String::compareTo);
        List<String> order = new ArrayList<>(universe);
        if (renumber != null)
            order.sort((a, b) -> Integer.compare(renumber.getOrDefault(a, universe.indexOf(a)),
                                                 renumber.getOrDefault(b, universe.indexOf(b))));
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < order.size(); i++) index.put(order.get(i), i);

        // constraint conflicts and strategy mixing
        Map<String, List<Model.RawConstraint>> byDof = new LinkedHashMap<>();
        for (Model.RawConstraint rc : raw.constraints()) byDof.computeIfAbsent(rc.dof(), x -> new ArrayList<>()).add(rc);
        for (var en : byDof.entrySet()) {
            List<String> vals = en.getValue().stream().map(Model.RawConstraint::value).distinct().toList();
            if (vals.size() > 1)
                diags.add(new Diag("CONSTRAINT_CONFLICT",
                        "dof " + en.getKey() + " constrained to multiple values " + vals
                                + " in constraint version " + raw.constraintVersion(),
                        List.of(en.getKey())));
        }
        List<String> strategies = raw.constraints().stream().map(Model.RawConstraint::strategy)
                .distinct().sorted().toList();
        boolean mixed = strategies.size() > 1 && forcedStrategy == null;
        if (mixed)
            diags.add(new Diag("MIXED_STRATEGY",
                    "constraint version " + raw.constraintVersion() + " mixes " + strategies
                            + "; elimination and penalty are never mixed: both candidate assemblies are produced and retained",
                    strategies));

        boolean pairwise = "PAIRWISE".equals(raw.floatMode());
        List<Entry> entries = new ArrayList<>();
        for (var en : byKey.entrySet()) {
            List<Tagged> ts = en.getValue();
            double[] xs = ts.stream().mapToDouble(t -> t.c().value()).toArray();
            double v = pairwise ? pairwiseSum(xs) : seqSum(xs);
            String[] key = en.getKey().split("\\|");
            entries.add(new Entry(key[0], key[1], index.get(key[0]), index.get(key[1]), v,
                    ts.stream().map(Tagged::c).toList()));
        }

        for (String d : universe) {
            boolean has = entries.stream().anyMatch(e -> e.rowDof().equals(d) || e.colDof().equals(d));
            if (!has) diags.add(new Diag("ISOLATED_DOF",
                    "dof " + d + " has no assembled contribution (isolated node)", List.of(d)));
        }

        String strategy = forcedStrategy != null ? forcedStrategy
                : (strategies.size() == 1 ? strategies.get(0) : "elimination");
        Result base = applyStrategy(strategy, entries, raw);
        List<Result> candidates = new ArrayList<>();
        if (mixed) {
            candidates.add(applyStrategy("elimination", entries, raw));
            candidates.add(applyStrategy("penalty", entries, raw));
        }
        return new Result(mixed ? "undecided" : strategy, base.entries(),
                concat(diags, base.diags()), candidates);
    }

    private static Result applyStrategy(String strategy, List<Entry> entries, Model.RawImport raw) {
        List<Diag> diags = new ArrayList<>();
        Map<String, Double> constrained = new LinkedHashMap<>();
        for (Model.RawConstraint rc : raw.constraints()) {
            if (!rc.strategy().equals(strategy)) continue;
            Double f = Model.unitFactor(rc.unit());
            if (f == null) {
                diags.add(new Diag("UNIT_UNCONVERTIBLE",
                        "constraint on " + rc.dof() + " unit '" + rc.unit() + "' unconvertible; raw value kept",
                        List.of(rc.dof())));
                continue;
            }
            constrained.putIfAbsent(rc.dof(), Double.parseDouble(rc.value()) * f);
        }
        List<Entry> out = new ArrayList<>();
        if (strategy.equals("elimination")) {
            for (Entry e : entries) {
                if (constrained.containsKey(e.rowDof()) || constrained.containsKey(e.colDof())) {
                    diags.add(new Diag("ELIMINATED_ENTRY",
                            "entry " + e.id() + " eliminated by constraint on "
                                    + (constrained.containsKey(e.rowDof()) ? e.rowDof() : e.colDof()),
                            List.of(e.id())));
                    continue;
                }
                out.add(e);
            }
        } else {
            double maxAbs = entries.stream().mapToDouble(e -> Math.abs(e.value())).max().orElse(1.0);
            double pen = maxAbs * 1e6;
            out.addAll(entries);
            for (String d : constrained.keySet()) {
                Entry diag = null;
                for (Entry e : out) if (e.rowDof().equals(d) && e.colDof().equals(d)) diag = e;
                if (diag == null) {
                    diags.add(new Diag("PENALTY_ON_ISOLATED_DOF",
                            "penalty applied to dof " + d + " which has no diagonal entry", List.of(d)));
                    continue;
                }
                out.remove(diag);
                out.add(new Entry(d, d, diag.row(), diag.col(), diag.value() + pen, diag.contribs()));
                diags.add(new Diag("PENALTY_APPLIED",
                        "penalty " + pen + " added to diagonal of dof " + d, List.of(d)));
            }
            out.sort((a, b) -> a.row() != b.row() ? Integer.compare(a.row(), b.row())
                    : Integer.compare(a.col(), b.col()));
        }
        return new Result(strategy, out, diags, List.of());
    }

    static double seqSum(double[] xs) { double s = 0; for (double x : xs) s += x; return s; }

    static double pairwiseSum(double[] xs) {
        if (xs.length == 0) return 0;
        if (xs.length == 1) return xs[0];
        int mid = xs.length / 2;
        return pairwiseSum(java.util.Arrays.copyOfRange(xs, 0, mid))
             + pairwiseSum(java.util.Arrays.copyOfRange(xs, mid, xs.length));
    }

    private static List<Diag> concat(List<Diag> a, List<Diag> b) {
        List<Diag> r = new ArrayList<>(a); r.addAll(b); return r;
    }
}
