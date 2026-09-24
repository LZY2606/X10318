package com.gsb.fea.core;

/** Same entry summed in two deterministic orders; non-zero absDiff proves that
 *  the chosen order materially decides the bit pattern of the result. */
public record FloatSensitivity(String entryUrn, double canonical, double grouped,
                               double absDiff, long ulps) {
}
