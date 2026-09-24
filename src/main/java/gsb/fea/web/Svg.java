package gsb.fea.web;

import gsb.fea.assembly.AssemblyDiff;
import gsb.fea.assembly.AssemblyResult;
import gsb.fea.assembly.MatrixEntry;
import gsb.fea.model.Element;
import gsb.fea.model.Node;
import gsb.fea.model.ProjectVersion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Server-side SVG renderers. Every drawn value carries its stable identity as data-uid. */
public final class Svg {

    public static String mesh(ProjectVersion version, java.util.Set<String> highlight) {
        List<Node> nodes = new ArrayList<>(version.nodes.values());
        nodes.sort(Comparator.comparingInt(n -> n.originalNumber));
        double scale = 90;
        double ox = 70;
        double oy = 70;
        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns='http://www.w3.org/2000/svg' width='560' height='460'")
                .append(" viewBox='0 0 560 460' class='mesh'>");
        svg.append("<rect width='560' height='460' fill='#0f141b' rx='8'/>");
        Map<String, double[]> pos = new TreeMap<>();
        for (Node node : nodes) {
            double x = ox + safe(node.x == null ? 0 : node.x.doubleValue()) * scale;
            double y = 460 - oy - safe(node.y == null ? 0 : node.y.doubleValue()) * scale;
            pos.put(node.uid, new double[]{x, y});
        }
        List<Element> elements = new ArrayList<>(version.elements.values());
        elements.sort(Comparator.comparingInt(e -> e.originalNumber));
        for (Element element : elements) {
            List<double[]> pts = new ArrayList<>();
            for (String nodeUid : element.nodeUids) {
                double[] p = pos.get(nodeUid);
                if (p != null) {
                    pts.add(p);
                }
            }
            boolean degenerate = new java.util.TreeSet<>(element.nodeUids).size()
                    < element.nodeUids.size();
            String cls = degenerate ? "elem degenerate" : "elem";
            if (pts.size() >= 2) {
                for (int i = 0; i + 1 < pts.size(); i++) {
                    double mx = (pts.get(i)[0] + pts.get(i + 1)[0]) / 2;
                    double my = (pts.get(i)[1] + pts.get(i + 1)[1]) / 2;
                    svg.append("<line class='").append(cls).append("' data-uid='")
                            .append(element.uid).append("' x1='").append(pts.get(i)[0])
                            .append("' y1='").append(pts.get(i)[1]).append("' x2='")
                            .append(pts.get(i + 1)[0]).append("' y2='").append(pts.get(i + 1)[1])
                            .append("'><title>").append(element.uid).append(" 原单元号 ")
                            .append(element.originalNumber).append(" (").append(element.elementType)
                            .append(")</title></line>");
                    svg.append("<text class='elabel' x='").append(mx + 6).append("' y='")
                            .append(my - 6).append("'>").append(element.uid).append("</text>");
                }
            } else if (pts.size() == 1) {
                svg.append("<circle class='").append(cls).append("' data-uid='")
                        .append(element.uid).append("' cx='").append(pts.get(0)[0] + 14)
                        .append("' cy='").append(pts.get(0)[1] - 14).append("' r='9' fill='none'>")
                        .append("<title>").append(element.uid).append(" 1D spring</title></circle>");
                svg.append("<text class='elabel' x='").append(pts.get(0)[0] + 26)
                        .append("' y='").append(pts.get(0)[1] - 18).append("'>")
                        .append(element.uid).append("</text>");
            }
        }
        for (Node node : nodes) {
            double[] p = pos.get(node.uid);
            boolean isolated = highlight != null && highlight.contains(node.uid);
            svg.append("<g class='node").append(isolated ? " isolated" : "")
                    .append("' data-uid='").append(node.uid).append("'>")
                    .append("<circle cx='").append(p[0]).append("' cy='").append(p[1])
                    .append("' r='10'/><title>").append(node.uid).append(" 原始节点号 ")
                    .append(node.originalNumber).append(" 坐标(").append(raw(node.x)).append(",")
                    .append(raw(node.y)).append(") ").append(unitOf(node.x))
                    .append(" 基准 ").append(node.coordinateFrame).append("</title>")
                    .append("<text class='nlabel' x='").append(p[0] - 8).append("' y='")
                    .append(p[1] + 4).append("'>").append(node.uid).append("</text></g>");
        }
        svg.append("</svg>");
        return svg.toString();
    }

    public static String pattern(AssemblyResult result) {
        int dim = Math.max(result.displayedDimension, 1);
        int cell = 34;
        int pad = 110;
        int size = dim * cell;
        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns='http://www.w3.org/2000/svg' width='").append(pad + size + 10)
                .append("' height='").append(pad + size + 10).append("' class='pattern'>");
        svg.append("<rect width='100%' height='100%' fill='#0f141b' rx='8'/>");
        Map<Integer, String> indexToDof = result.indexToDof;
        Map<Integer, Integer> toDisplayed = result.physicalToDisplayed;
        String[] displayedDof = new String[dim];
        for (int p = 0; p < result.physicalDimension; p++) {
            int d = toDisplayed.getOrDefault(p, p);
            if (d >= 0 && d < dim) {
                displayedDof[d] = indexToDof.get(p);
            }
        }
        for (int i = 0; i < dim; i++) {
            String dof = displayedDof[i] == null ? "?" : displayedDof[i];
            svg.append("<text class='axis' x='").append(pad + i * cell + cell / 2 - 14)
                    .append("' y='").append(pad - 8).append("'>").append(dof).append("</text>");
            svg.append("<text class='axis' x='8' y='").append(pad + i * cell + cell / 2 + 4)
                    .append("'>").append(dof).append("</text>");
        }
        for (MatrixEntry entry : result.entries) {
            String cls = entry.numeric ? "nz" : "nz bad";
            if (entry.summationMode != null && entry.summationMode.startsWith("CONSTRAINT")) {
                cls = "nz constraint";
            }
            svg.append("<rect class='").append(cls).append("' data-uid='").append(entry.uid)
                    .append("' data-row='").append(entry.physicalRow).append("' data-col='")
                    .append(entry.physicalCol).append("' x='").append(pad + entry.displayedCol * cell)
                    .append("' y='").append(pad + entry.displayedRow * cell)
                    .append("' width='").append(cell - 3).append("' height='").append(cell - 3)
                    .append("'><title>").append(entry.uid).append(" = ")
                    .append(entry.numeric ? entry.exactValue : "不可转换(原值保留)")
                    .append(" ").append(result.matrixUnit).append("</title></rect>");
        }
        svg.append("</svg>");
        return svg.toString();
    }

    public static String diff(AssemblyDiff diff, int dim) {
        int cell = 34;
        int pad = 20;
        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns='http://www.w3.org/2000/svg' width='").append(pad + dim * cell + 10)
                .append("' height='").append(pad + dim * cell + 10).append("' class='diff'>");
        svg.append("<rect width='100%' height='100%' fill='#0f141b' rx='8'/>");
        for (AssemblyDiff.EntryDelta delta : diff.entryDeltas) {
            String cls = switch (delta.kind) {
                case "ADDED" -> "d-add";
                case "REMOVED" -> "d-del";
                default -> "d-chg";
            };
            svg.append("<rect class='").append(cls).append("' data-uid='")
                    .append(delta.leftUid != null ? delta.leftUid : delta.rightUid)
                    .append("' x='").append(pad + delta.physicalCol * cell).append("' y='")
                    .append(pad + delta.physicalRow * cell).append("' width='").append(cell - 3)
                    .append("' height='").append(cell - 3).append("'><title>")
                    .append(delta.kind).append(" (").append(delta.rowDofId).append(",")
                    .append(delta.colDofId).append(") ")
                    .append(delta.leftValue).append(" -> ").append(delta.rightValue)
                    .append("</title></rect>");
        }
        svg.append("</svg>");
        return svg.toString();
    }

    private static double safe(double value) {
        return Double.isNaN(value) ? 0 : value;
    }

    private static String raw(gsb.fea.util.RawDecimal value) {
        return value == null ? "?" : value.raw();
    }

    private static String unitOf(gsb.fea.util.RawDecimal value) {
        return value == null ? "" : value.unit();
    }

    private Svg() {
    }
}
