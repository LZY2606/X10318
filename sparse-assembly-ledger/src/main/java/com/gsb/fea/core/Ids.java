package com.gsb.fea.core;

/** Stable, renumbering-proof identity scheme used everywhere, including UI. */
public final class Ids {
    private Ids() {}

    public static String node(String nodeId) { return "node:" + nodeId; }
    public static String element(String elementId) { return "el:" + elementId; }
    public static String dof(String dofId) { return "dof:" + dofId; }
    public static String map(String elementId) { return "map:" + elementId; }
    public static String term(String elementId, int a, int b) {
        return "term:" + elementId + ":" + a + "," + b;
    }
    public static String contribution(String elementId, int a, int b) {
        return "ct:" + elementId + ":" + a + "," + b;
    }
    public static String constraint(String id) { return "con:" + id; }
    public static String entry(String rowDof, String colDof) {
        return "entry:" + rowDof + "," + colDof;
    }
}
