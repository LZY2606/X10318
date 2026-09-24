package gsb.fea.casepack;

import gsb.fea.util.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Writes the checked-in offline fixture casepack/fea-sparse-ledger.json. */
public final class CasePackGenerator {

    public static void main(String[] args) throws Exception {
        CasePack pack = build();
        Path out = Path.of("casepack/fea-sparse-ledger.json");
        Files.createDirectories(out.getParent());
        Json.writeFile(out, pack);
        System.out.println("wrote " + out + " (" + Files.size(out) + " bytes, "
                + pack.versions.size() + " versions, " + pack.replay.size() + " replay steps)");
    }

    public static CasePack build() {
        CasePack pack = new CasePack();
        pack.narrative = "计算工程小组导入 element connectivity、局部矩阵、自由度映射和边界约束，"
                + "装配全局稀疏矩阵并追踪每个非零项来源；基线保留原单元编号、局部自由度顺序、"
                + "局部矩阵值、全局映射、约束版本、单位和浮点模式，随后一次参数修订、"
                + "一次局部源数据改动、一次互相冲突的人工决定，回放经过重复自由度、退化单元、"
                + "孤立节点、约束冲突、浮点求和顺序、重编号和局部矩阵非对称。";

        gsb.fea.model.ProjectVersion v1 = FixtureData.baseline();
        gsb.fea.model.ProjectVersion v2 = FixtureData.parameterRevision(v1);
        gsb.fea.model.ProjectVersion v3 = FixtureData.localSourceChange(v2);
        gsb.fea.model.ProjectVersion v4a = FixtureData.conflictBranchA(v3);
        gsb.fea.model.ProjectVersion v4b = FixtureData.conflictBranchB(v3);

        pack.versions.add(entry("IMPORT_BASELINE", "engineering",
                "initial import preserves original representation", v1));
        pack.versions.add(entry("EDIT_PARAM", "engineering",
                "parameter revision: prescribed displacement 0.002 m -> 0.003 m", v2));
        pack.versions.add(entry("EDIT_SOURCE_LOCAL", "engineering",
                "local source change: E2 local matrix entry becomes unconvertible", v3));
        pack.versions.add(entry("EDIT_RENUMBER", "engineer-a",
                "branch A: apply renumbering REN-1 (indices only)", v4a));
        pack.versions.add(entry("EDIT_CONSTRAINTS", "engineer-b",
                "branch B: select mixed-strategy constraint set BC-3", v4b));

        int n = 0;
        pack.replay.add(step(++n, "LOAD", "v1", "导入基线并核对原始表示",
                Map.of("elements", 5, "mappedDofs", 8)));
        pack.replay.add(assemble(++n, "D1", "v1", "DOUBLE_CANONICAL", null, null, null,
                "规范顺序装配：退化单元/孤立节点/重复自由度/非对称均留诊",
                Map.of("complete", true,
                        "diagnosticsContains", List.of("ISOLATED_DOF", "DEGENERATE_ELEMENT",
                                "DUPLICATE_DOF", "LOCAL_MATRIX_ASYMMETRIC"),
                        "entry", Map.of("rowDof", "N2.ux", "colDof", "N2.ux",
                                "double", 5.0, "exact", "5"))));
        pack.replay.add(assemble(++n, "D2", "v1", "DOUBLE_REVERSED", null, null, null,
                "仅改浮点求和顺序：同一物理量给出不同舍入结果，差异必须可见",
                Map.of("entry", Map.of("rowDof", "N2.ux", "colDof", "N2.ux",
                        "double", 4.0, "exact", "5"))));
        pack.replay.add(assemble(++n, "D3", "v1", "DOUBLE_CANONICAL", null, "REN-1", null,
                "重编号只改变显示索引，物理对应与数值不变",
                Map.of("renumbered", true,
                        "entry", Map.of("rowDof", "N2.ux", "colDof", "N2.ux",
                                "double", 5.0, "exact", "5"))));
        pack.replay.add(step(++n, "LOAD", "v2", "参数修订：约束版本切换 BC-1 -> BC-2",
                Map.of("version", "v2")));
        pack.replay.add(assemble(++n, "D4", "v2", "DOUBLE_CANONICAL", null, null, null,
                "参数修订后的装配：N4.uy 约束影响行更新",
                Map.of("complete", true, "constraintEffect", "BC-003")));
        pack.replay.add(step(++n, "LOAD", "v3", "局部源数据改动：E2 局部矩阵一项不可转换",
                Map.of("version", "v3")));
        pack.replay.add(assemble(++n, "D5", "v3", "DOUBLE_CANONICAL", null, null, null,
                "不可转换值留在原位置并附诊断，不丢值",
                Map.of("diagnosticsContains", List.of("UNCONVERTIBLE_VALUE"),
                        "incompleteEntry", Map.of("rowDof", "N2.ux", "colDof", "N3.ux"))));
        CasePack.ReplayStep analyze = step(++n, "ANALYZE", null,
                "依赖图传播：v2->v3 只影响 E2 相关自由度",
                Map.of("affectedDofs", List.of("N2.ux", "N2.uy", "N3.ux", "N3.uy"),
                        "notAffected", List.of("N4.ux", "N4.uy")));
        analyze.on = "v2->v3";
        pack.replay.add(analyze);
        CasePack.ReplayStep saveA = step(++n, "SAVE", null,
                "工程师 A 从 v3 保存分支 v4a（重编号选择）", Map.of("saved", "v4a"));
        saveA.version = "v4a";
        saveA.on = "v3";
        saveA.actor = "engineer-a";
        pack.replay.add(saveA);
        CasePack.ReplayStep saveB = step(++n, "SAVE", null,
                "工程师 B 从同一 v3 保存分支 v4b：出现可解决的冲突", Map.of("conflict", true));
        saveB.version = "v4b";
        saveB.on = "v3";
        saveB.actor = "engineer-b";
        pack.replay.add(saveB);
        CasePack.ReplayStep resolve = step(++n, "RESOLVE", null,
                "解决冲突：选择 v4a 继续，v4b 保留不覆盖", Map.of("chosen", "v4a",
                "unchosenRetained", "v4b"));
        resolve.choose = "v4a";
        pack.replay.add(resolve);
        pack.replay.add(assemble(++n, "D8", "v4b", "DOUBLE_CANONICAL", null, null, null,
                "混合策略约束集被阻塞：消元与 penalty 不混用，两个候选保留",
                Map.of("blocked", true,
                        "diagnosticsContains", List.of("CONSTRAINT_STRATEGY_CONFLICT"),
                        "candidates", 2)));
        pack.replay.add(assemble(++n, "D9", "v4b", "DOUBLE_CANONICAL", "ELIMINATION", null, null,
                "裁定采用消元策略后装配成功，penalty 候选仍保留在档案中",
                Map.of("complete", true)));
        pack.replay.add(assemble(++n, "D6", "v1", "DOUBLE_CANONICAL", null, null, "BC-4",
                "约束冲突：同一自由度两个约束，阻塞并保留两个合法候选",
                Map.of("blocked", true,
                        "diagnosticsContains", List.of("CONSTRAINT_CONFLICT"),
                        "candidates", 2)));
        CasePack.ReplayStep decide = step(++n, "DECIDE", null,
                "人工裁定：保留 BC-005，拒绝 BC-006（未采纳候选保留）",
                Map.of("decision", "BC-005", "rejectedKept", true));
        decide.on = "v1";
        decide.choose = "BC-005";
        pack.replay.add(decide);
        pack.replay.add(assemble(++n, "D7", "v5", "DOUBLE_CANONICAL", null, null, null,
                "裁定后的装配可追溯：约束影响只来自被采纳的约束",
                Map.of("complete", true, "constraintEffect", "BC-005")));
        pack.replay.add(step(++n, "EXPORT_IMPORT", null,
                "导出、删除本地数据库、再导入：身份/顺序/来源关系保持",
                Map.of("identityPreserved", true)));
        CasePack.ReplayStep back = step(++n, "CHECKOUT", null,
                "回到历史事件对应版本：恢复当时的依赖版本与可见结论",
                Map.of("version", "v1"));
        back.version = "v1";
        pack.replay.add(back);
        pack.replay.add(assemble(++n, "D10", "v1", "DOUBLE_CANONICAL", null, null, null,
                "历史版本重放结果与 D1 一致，不被最新计算覆盖",
                Map.of("entry", Map.of("rowDof", "N2.ux", "colDof", "N2.ux",
                        "double", 5.0, "exact", "5"))));
        return pack;
    }

    private static CasePack.VersionEntry entry(String eventType, String actor, String rationale,
                                               gsb.fea.model.ProjectVersion version) {
        CasePack.VersionEntry entry = new CasePack.VersionEntry();
        entry.eventType = eventType;
        entry.actor = actor;
        entry.rationale = rationale;
        entry.version = version;
        return entry;
    }

    private static CasePack.ReplayStep step(int n, String action, String id, String title,
                                            Map<String, Object> expect) {
        CasePack.ReplayStep step = new CasePack.ReplayStep();
        step.n = n;
        step.action = action;
        step.id = id;
        step.title = title;
        step.expect = expect;
        return step;
    }

    private static CasePack.ReplayStep assemble(int n, String id, String on, String summation,
                                                String strategy, String renumber,
                                                String constraintRevision, String title,
                                                Map<String, Object> expect) {
        CasePack.ReplayStep step = step(n, "ASSEMBLE", id, title, expect);
        step.on = on;
        step.summation = summation;
        step.strategy = strategy;
        step.renumber = renumber;
        step.constraintRevision = constraintRevision;
        return step;
    }

    private CasePackGenerator() {
    }
}
