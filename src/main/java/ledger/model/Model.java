package ledger.model;

import java.util.List;
import java.util.Map;

/** 领域模型：全部源数据保留原始表示（字符串原值、原单位、原坐标基准），派生层才规范化。 */
public final class Model {

    /** 单元源数据：原单元编号、连接、局部自由度顺序、局部矩阵原始字符串值、单位、坐标基准。 */
    public record ElemSrc(
            String elemKey,            // 稳定身份，如 "E2"
            String origId,             // 原单元编号，如 "EL-1002"
            List<String> conn,         // 节点连接（保留重复以诊断退化单元）
            List<String> localDofOrder,// 局部自由度顺序
            List<List<String>> localMatrix, // 局部矩阵原始字符串值
            String unit,               // 原单位
            String coordBasis          // 坐标基准
    ) {}

    /** 边界约束：携带约束版本、单位、策略（elimination|penalty）。 */
    public record ConstraintSrc(
            String id,
            String dofKey,
            String type,
            String rawValue,
            String unit,
            String strategy,          // elimination | penalty
            String constraintVersion  // 约束版本，如 "BC-2026A"
    ) {}

    /** 一个版本的完整源数据快照（不可变）。 */
    public record Snapshot(
            String versionId,
            String parentId,
            int seq,
            String label,
            String kind,              // IMPORT | REVISION | SOURCE_CHANGE | DECISION | CHECKOUT | CONFLICT_BRANCH
            List<ElemSrc> elements,
            Map<String, List<String>> dofMaps,   // elemKey -> 全局自由度 key 列表（与 localDofOrder 对齐）
            List<ConstraintSrc> constraints,
            List<String> dofUniverse,            // 已知全部自由度 key（来自节点表）
            List<String> dofOrder,               // 显式重编号顺序；空 = 按 key 排序
            Map<String, String> params           // float.mode / penalty.factor / unit.base
    ) {
        public String param(String key, String dflt) {
            String v = params == null ? null : params.get(key);
            return v == null ? dflt : v;
        }
    }

    /** 单项贡献：非零项来源链的最小单元。 */
    public record Contribution(
            String elemKey,
            String origElemId,
            int localRow,
            int localCol,
            String rawValue,   // 原始字符串
            String unit,       // 原单位
            double value,      // 规范化后的值；不可转换时为 NaN
            int order,         // 确定性求和顺序
            String source      // element | penalty
    ) {}

    /** 全局稀疏矩阵非零项。 */
    public record AssemblyEntry(
            String rowKey,
            String colKey,
            int row,
            int col,
            double value,
            String valueText,
            List<Contribution> contribs,
            String origin,     // 为何仍有效 / 为何重算
            boolean contested  // 存在未裁定的不确定状态
    ) {}

    /** 诊断事件（机器可读）。 */
    public record Diagnostic(
            String code,       // REPEATED_DOF / DEGENERATE_ELEMENT / ISOLATED_DOF / CONSTRAINT_CONFLICT / ...
            String objectRef,  // 稳定对象引用，如 "E2" 或 "n4:u"
            String severity,   // INFO | WARN | ERROR
            String message,
            Map<String, String> payload
    ) {}

    /** 装配结果。 */
    public record AssemblyResult(
            List<AssemblyEntry> entries,
            List<Diagnostic> diagnostics,
            Map<String, Integer> dofIndex,   // key -> 行/列索引（重编号只改索引）
            String floatMode,
            String constraintStrategy,       // 实际应用的策略；混用时为 "CONTESTED"
            List<String> constraintImpact    // 被约束影响的 entry 标识 "r|c"
    ) {}
}
