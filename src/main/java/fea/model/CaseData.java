package fea.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Raw imported case data. Original string representations are preserved verbatim;
 *  normalization happens only in the derived layer. */
public final class CaseData {
    public String unitSystem = "unknown";
    public String floatMode = "ieee754-double";
    public String sumOrder = "sorted-contribution"; // deterministic canonical order
    public String coordinateBasis = "global-xy";
    public final List<Node> nodes = new ArrayList<>();
    public final List<Element> elements = new ArrayList<>();
    public final List<Mapping> mappings = new ArrayList<>();
    public ConstraintSet constraints = new ConstraintSet();

    public static final class Node {
        public String id; public String x; public String y; public String unit;
        public double xn, yn; public boolean convertible = true;
    }
    public static final class Element {
        public String key;                 // original element id, e.g. "E-101"
        public String type = "Q4";
        public List<String> connectivity = new ArrayList<>();   // original node ids
        public List<String> localDofOrder = new ArrayList<>();  // local dof labels
        public List<List<String>> localMatrix = new ArrayList<>(); // raw strings preserved
        public String unit = "N/mm";
    }
    public static final class Mapping {
        public String element; public String local; public String global; // global dof id (physical identity)
    }
    public static final class ConstraintSet {
        public String version = "BC-v1";
        public String strategy = "elimination"; // elimination | penalty (never mixed)
        public String penaltyFactor = "1.0e8";
        public List<Entry> entries = new ArrayList<>();
        public static final class Entry { public String dof; public String value; public String unit = "mm"; }
    }

    public static CaseData fromJson(JsonNode r) {
        CaseData c = new CaseData();
        JsonNode meta = r.path("meta");
        c.unitSystem = meta.path("unitSystem").asText(c.unitSystem);
        c.floatMode = meta.path("floatMode").asText(c.floatMode);
        c.sumOrder = meta.path("sumOrder").asText(c.sumOrder);
        c.coordinateBasis = meta.path("coordinateBasis").asText(c.coordinateBasis);
        for (JsonNode n : r.path("nodes")) {
            Node nd = new Node();
            nd.id = n.path("id").asText();
            nd.x = n.path("x").asText("0"); nd.y = n.path("y").asText("0");
            nd.unit = n.path("unit").asText("mm");
            try { nd.xn = UnitConv.toMm(nd.x, nd.unit); nd.yn = UnitConv.toMm(nd.y, nd.unit); }
            catch (IllegalArgumentException e) { nd.convertible = false; }
            c.nodes.add(nd);
        }
        for (JsonNode e : r.path("elements")) {
            Element el = new Element();
            el.key = e.path("key").asText();
            el.type = e.path("type").asText("Q4");
            e.path("connectivity").forEach(n -> el.connectivity.add(n.asText()));
            e.path("localDofOrder").forEach(d -> el.localDofOrder.add(d.asText()));
            for (JsonNode row : e.path("localMatrix")) {
                List<String> rr = new ArrayList<>();
                row.forEach(v -> rr.add(v.isTextual() ? v.asText() : v.toString()));
                el.localMatrix.add(rr);
            }
            el.unit = e.path("unit").asText("N/mm");
            c.elements.add(el);
        }
        for (JsonNode m : r.path("mapping")) {
            Mapping mp = new Mapping();
            mp.element = m.path("element").asText();
            mp.local = m.path("local").asText();
            mp.global = m.path("global").asText();
            c.mappings.add(mp);
        }
        JsonNode cs = r.path("constraints");
        c.constraints.version = cs.path("version").asText("BC-v1");
        c.constraints.strategy = cs.path("strategy").asText("elimination");
        c.constraints.penaltyFactor = cs.path("penaltyFactor").asText("1.0e8");
        for (JsonNode en : cs.path("entries")) {
            ConstraintSet.Entry ce = new ConstraintSet.Entry();
            ce.dof = en.path("dof").asText();
            ce.value = en.path("value").isTextual() ? en.path("value").asText() : en.path("value").toString();
            ce.unit = en.path("unit").asText("mm");
            c.constraints.entries.add(ce);
        }
        return c;
    }
}
