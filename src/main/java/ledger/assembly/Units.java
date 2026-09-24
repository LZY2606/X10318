package ledger.assembly;

import java.util.Map;
import java.util.OptionalDouble;

/** 单位换算：仅在派生层做规范化；无法换算的值保留原表示并附诊断。 */
public final class Units {
    /** 已知换算表：from->to 的线性因子。 */
    private static final Map<String, Double> FACTORS = Map.of(
            "mm->m", 0.001,
            "m->mm", 1000.0,
            "N/mm->N/m", 1000.0,
            "N/m->N/mm", 0.001,
            "kN/m->N/m", 1000.0,
            "N/m->kN/m", 0.001
    );

    private Units() {}

    /** 同单位或已知换算返回值；否则 empty（调用方保留原值并诊断）。 */
    public static OptionalDouble convert(double value, String from, String to) {
        if (from == null || to == null) return OptionalDouble.empty();
        if (from.equals(to)) return OptionalDouble.of(value);
        Double f = FACTORS.get(from + "->" + to);
        return f == null ? OptionalDouble.empty() : OptionalDouble.of(value * f);
    }

    public static boolean convertible(String from, String to) {
        if (from == null || to == null) return false;
        return from.equals(to) || FACTORS.containsKey(from + "->" + to);
    }
}
