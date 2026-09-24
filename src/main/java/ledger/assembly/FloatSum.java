package ledger.assembly;

import java.util.List;

/** 浮点求和模式：求和顺序与精度是显式模式，不同模式的结果差异被记录而非吞掉。 */
public final class FloatSum {
    public static final String F64_SEQ = "f64-seq";
    public static final String F64_PAIRWISE = "f64-pairwise";
    public static final String F32_SEQ = "f32-seq";

    private FloatSum() {}

    public static boolean known(String mode) {
        return F64_SEQ.equals(mode) || F64_PAIRWISE.equals(mode) || F32_SEQ.equals(mode);
    }

    /** values 已按确定性顺序（order 字段）排列。 */
    public static double sum(String mode, List<Double> values) {
        switch (mode) {
            case F64_PAIRWISE: return pairwise(values, 0, values.size());
            case F32_SEQ: {
                float acc = 0f;
                for (double v : values) acc += (float) v;
                return acc;
            }
            case F64_SEQ:
            default: {
                double acc = 0.0;
                for (double v : values) acc += v;
                return acc;
            }
        }
    }

    private static double pairwise(List<Double> v, int lo, int hi) {
        int n = hi - lo;
        if (n == 0) return 0.0;
        if (n == 1) return v.get(lo);
        int mid = lo + n / 2;
        return pairwise(v, lo, mid) + pairwise(v, mid, hi);
    }
}
