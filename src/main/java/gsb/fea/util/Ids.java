package gsb.fea.util;

public final class Ids {
    private Ids() {
    }

    public static String node(int number) {
        return "N" + number;
    }

    public static String dof(String nodeId, String axis) {
        return nodeId + "." + axis;
    }

    public static String element(int originalNumber) {
        return "E" + originalNumber;
    }

    public static String localMatrix(String elementId, String revision) {
        return "LM:" + elementId + ":" + revision;
    }

    public static String mapping(String revision) {
        return "MAP:" + revision;
    }

    public static String constraints(String revision) {
        return "BC:" + revision;
    }

    public static String renumber(String revision) {
        return "REN:" + revision;
    }

    public static String entry(String derivationId, int row, int col) {
        return derivationId + ":K(" + row + "," + col + ")";
    }

    public static String contribution(String elementId, int row, int col) {
        return "C:" + elementId + ":(" + row + "," + col + ")";
    }
}
