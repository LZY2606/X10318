package org.fealedger.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Imports the engineering team's payload into {@link Raw} objects without any
 * normalization: values remain their original textual representation and each
 * quantity carries its own unit / coordinate basis. Malformed numeric values
 * are kept in place (rawUnparsable flag) rather than dropped, so a chart can
 * never silently erase data; diagnostics are produced at derivation time.
 */
public final class ImportExport {
    private ImportExport() {}

    public static final class ImportResult {
        public final Raw.Project project = new Raw.Project();
        public final List<Raw.Doc> importNotes = new ArrayList<>();
    }

    public static ImportResult importProject(String json) {
        return fromMap(Json.parseObject(json));
    }

    public static ImportResult fromMap(LinkedHashMap<String, Object> root) {
        ImportResult r = new ImportResult();
        Raw.Project p = r.project;
        Raw.Doc meta = new Raw.Doc();
        meta.m.putAll(asObj(root.get("meta"), "meta"));
        p.id = str(meta, "id", "project-1");
        p.name = str(meta, "name", "untitled");
        p.revision = str(meta, "revision", "0");
        p.defaultCoordinateBasis = str(meta, "coordinateBasis", "CARTESIAN");
        p.defaultUnit = str(meta, "unit", "mm");
        p.defaultFloatMode = str(meta, "floatMode", "IEEE754:binary64:ROUND_HALF_EVEN");
        p.attributes.m.putAll(meta.m);

        if (root.get("constraintStrategy") != null) {
            p.constraintStrategy = root.get("constraintStrategy").toString();
        }

        for (Object no : asList(root.get("nodes"), "nodes")) {
            LinkedHashMap<String, Object> nm = asObj(no, "node");
            String id = str(nm, "id", null);
            if (id == null) { r.importNotes.add(note("NODE_MISSING_ID", "node skipped (no id)", nm)); continue; }
            List<Object> coordList = asList(nm.get("coords"), "node.coords");
            String[] coords = new String[coordList.size()];
            for (int k = 0; k < coordList.size(); k++) coords[k] = coordList.get(k).toString(); // verbatim
            String basis = str(nm, "coordinateBasis", p.defaultCoordinateBasis);
            String unit = str(nm, "unit", p.defaultUnit);
            Raw.Doc attrs = new Raw.Doc();
            if (nm.get("attributes") instanceof LinkedHashMap<?, ?> am) attrs.m.putAll(castObj(am));
            p.nodes.put(id, new Raw.Node(id, str(nm, "originalNumber", id), coords, basis, unit, attrs));
        }

        for (Object eo : asList(root.get("elements"), "elements")) {
            LinkedHashMap<String, Object> em = asObj(eo, "element");
            String id = str(em, "id", null);
            if (id == null) { r.importNotes.add(note("ELEMENT_MISSING_ID", "element skipped (no id)", em)); continue; }
            List<String> nodes = new ArrayList<>();
            for (Object n : asList(em.get("nodeIds"), "element.nodeIds")) nodes.add(n.toString());
            List<String> labels = new ArrayList<>();
            Object schema = em.get("dofSchema");
            if (schema != null) for (Object l : asList(schema, "element.dofSchema")) labels.add(l.toString());
            else for (Object l : asList(em.get("dofLabels"), "element.dofLabels")) labels.add(l.toString());
            if (labels.isEmpty()) labels.add("u");
            Raw.Doc attrs = new Raw.Doc();
            if (em.get("attributes") instanceof LinkedHashMap<?, ?> am) attrs.m.putAll(castObj(am));
            p.elements.put(id, new Raw.Element(id, str(em, "originalNumber", id),
                    str(em, "type", "generic"), nodes, labels,
                    em.get("localMatrixId") == null ? null : em.get("localMatrixId").toString(), attrs));
        }

        for (Object mo : asList(root.get("localMatrices"), "localMatrices")) {
            LinkedHashMap<String, Object> mm = asObj(mo, "localMatrix");
            String id = str(mm, "id", null);
            if (id == null) { r.importNotes.add(note("MATRIX_MISSING_ID", "matrix skipped (no id)", mm)); continue; }
            int size = (int) Math.floor(asNum(mm.get("size"), 0));
            List<Raw.LocalMatrix.Entry> entries = new ArrayList<>();
            for (Object en : asList(mm.get("entries"), "localMatrix.entries")) {
                LinkedHashMap<String, Object> e = asObj(en, "entry");
                int row = (int) Math.floor(asNum(e.get("row"), -1));
                int col = (int) Math.floor(asNum(e.get("col"), -1));
                String raw = e.get("value") == null ? null : e.get("value").toString();
                String unit = str(e, "unit", null);
                String fm = str(e, "floatMode", p.defaultFloatMode);
                entries.add(new Raw.LocalMatrix.Entry(row, col, raw, unit, fm));
            }
            Raw.Doc attrs = new Raw.Doc();
            if (mm.get("attributes") instanceof LinkedHashMap<?, ?> am) attrs.m.putAll(castObj(am));
            p.matrices.put(id, new Raw.LocalMatrix(id, str(mm, "version", "1"), size, entries, attrs));
        }

        for (Object mo : asList(root.get("dofMap"), "dofMap")) {
            LinkedHashMap<String, Object> dm = asObj(mo, "dofMap entry");
            String el = str(dm, "elementId", null);
            int local = (int) Math.floor(asNum(dm.get("local"), -1));
            Object g = dm.get("globalDofId");
            p.dofMap.add(new Raw.DofMapEntry(el, local, g == null ? null : g.toString()));
        }

        for (Object co : asList(root.get("constraints"), "constraints")) {
            LinkedHashMap<String, Object> cm = asObj(co, "constraint");
            String id = str(cm, "id", null);
            if (id == null) { r.importNotes.add(note("CONSTRAINT_MISSING_ID", "constraint skipped", cm)); continue; }
            Raw.ConstraintKind kind = Raw.ConstraintKind.valueOf(
                    str(cm, "kind", "FIXED").toUpperCase(java.util.Locale.ROOT));
            List<String> coupled = new ArrayList<>();
            List<String> coeffs = new ArrayList<>();
            if (cm.get("coupledDofIds") != null) for (Object x : asList(cm.get("coupledDofIds"), "coupled")) coupled.add(x.toString());
            if (cm.get("coeffs") != null) for (Object x : asList(cm.get("coeffs"), "coeffs")) coeffs.add(x.toString());
            Raw.Doc attrs = new Raw.Doc();
            if (cm.get("attributes") instanceof LinkedHashMap<?, ?> am) attrs.m.putAll(castObj(am));
            p.constraints.put(id, new Raw.Constraint(id, str(cm, "version", "1"), kind,
                    str(cm, "globalDofId", null),
                    cm.get("value") == null ? null : cm.get("value").toString(),
                    str(cm, "unit", p.defaultUnit), str(cm, "floatMode", p.defaultFloatMode),
                    coupled, coeffs, attrs));
        }
        return r;
    }

    /**
     * Serializes a raw project back to JSON preserving original representations,
     * units, coordinate bases and float modes. Export and import are inverse
     * operations for the fields that carry identity.
     */
    public static LinkedHashMap<String, Object> toMap(Raw.Project p) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        LinkedHashMap<String, Object> meta = new LinkedHashMap<>(p.attributes.m);
        meta.put("id", p.id); meta.put("name", p.name); meta.put("revision", p.revision);
        meta.put("coordinateBasis", p.defaultCoordinateBasis);
        meta.put("unit", p.defaultUnit);
        meta.put("floatMode", p.defaultFloatMode);
        root.put("meta", meta);
        if (p.constraintStrategy != null) root.put("constraintStrategy", p.constraintStrategy);

        List<Object> nodes = new ArrayList<>();
        for (Raw.Node n : p.nodes.values()) {
            LinkedHashMap<String, Object> nm = new LinkedHashMap<>();
            nm.put("id", n.id); nm.put("originalNumber", n.originalNumber);
            nm.put("coords", new ArrayList<>(java.util.Arrays.asList(n.coords)));
            nm.put("coordinateBasis", n.coordinateBasis); nm.put("unit", n.unit);
            if (!n.attributes.m.isEmpty()) nm.put("attributes", new LinkedHashMap<>(n.attributes.m));
            nodes.add(nm);
        }
        root.put("nodes", nodes);

        List<Object> els = new ArrayList<>();
        for (Raw.Element e : p.elements.values()) {
            LinkedHashMap<String, Object> em = new LinkedHashMap<>();
            em.put("id", e.id); em.put("originalNumber", e.originalNumber); em.put("type", e.type);
            em.put("nodeIds", new ArrayList<>(e.nodeIds));
            em.put("dofSchema", new ArrayList<>(e.dofLabels));
            em.put("localMatrixId", e.localMatrixId);
            if (!e.attributes.m.isEmpty()) em.put("attributes", new LinkedHashMap<>(e.attributes.m));
            els.add(em);
        }
        root.put("elements", els);

        List<Object> mats = new ArrayList<>();
        for (Raw.LocalMatrix m : p.matrices.values()) {
            LinkedHashMap<String, Object> mm = new LinkedHashMap<>();
            mm.put("id", m.id); mm.put("version", m.version); mm.put("size", m.size);
            List<Object> entries = new ArrayList<>();
            for (Raw.LocalMatrix.Entry en : m.entries) {
                LinkedHashMap<String, Object> e = new LinkedHashMap<>();
                e.put("row", en.row); e.put("col", en.col); e.put("value", en.rawValue);
                if (en.unit != null) e.put("unit", en.unit);
                e.put("floatMode", en.floatMode);
                entries.add(e);
            }
            mm.put("entries", entries);
            if (!m.attributes.m.isEmpty()) mm.put("attributes", new LinkedHashMap<>(m.attributes.m));
            mats.add(mm);
        }
        root.put("localMatrices", mats);

        List<Object> maps = new ArrayList<>();
        for (Raw.DofMapEntry d : p.dofMap) {
            LinkedHashMap<String, Object> dm = new LinkedHashMap<>();
            dm.put("elementId", d.elementId); dm.put("local", d.localIndex);
            dm.put("globalDofId", d.globalDofId);
            maps.add(dm);
        }
        root.put("dofMap", maps);

        List<Object> cons = new ArrayList<>();
        for (Raw.Constraint c : p.constraints.values()) {
            LinkedHashMap<String, Object> cm = new LinkedHashMap<>();
            cm.put("id", c.id); cm.put("version", c.version); cm.put("kind", c.kind.name());
            cm.put("globalDofId", c.globalDofId); cm.put("value", c.rawValue);
            cm.put("unit", c.unit); cm.put("floatMode", c.floatMode);
            if (!c.coupledDofIds.isEmpty()) {
                cm.put("coupledDofIds", new ArrayList<>(c.coupledDofIds));
                cm.put("coeffs", new ArrayList<>(c.coeffs));
            }
            if (!c.attributes.m.isEmpty()) cm.put("attributes", new LinkedHashMap<>(c.attributes.m));
            cons.add(cm);
        }
        root.put("constraints", cons);
        return root;
    }

    private static Raw.Doc note(String code, String message, Object subject) {
        Raw.Doc d = new Raw.Doc();
        d.set("code", code).set("message", message).set("subject", subject);
        return d;
    }

    private static String str(Map<String, Object> m, String k, String dflt) {
        Object v = m.get(k);
        return v == null ? dflt : v.toString();
    }

    private static double asNum(Object v, double dflt) { return v instanceof Number n ? n.doubleValue() : dflt; }

    private static List<Object> asList(Object v, String where) {
        if (v instanceof List<?> l) { @SuppressWarnings("unchecked") List<Object> cast = (List<Object>) l; return cast; }
        if (v == null) return new ArrayList<>();
        throw new IllegalArgumentException(where + " must be a list");
    }

    private static LinkedHashMap<String, Object> asObj(Object v, String where) {
        if (v instanceof LinkedHashMap<?, ?> m) { @SuppressWarnings("unchecked") LinkedHashMap<String, Object> c = (LinkedHashMap<String, Object>) m; return c; }
        throw new IllegalArgumentException(where + " must be an object");
    }

    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String, Object> castObj(LinkedHashMap<?, ?> am) {
        return (LinkedHashMap<String, Object>) am;
    }
}
