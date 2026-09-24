package com.gsb.fea.core;

/**
 * One raw local-matrix term's contribution to one global entry.
 * ordinal is the deterministic position at which this contribution is summed
 * (element import order, then local row, then local column), so repeated
 * contributions always add in one fixed order regardless of map iteration.
 */
public record Contribution(String urn, String sparseEntryUrn, String elementId,
                           int localRow, int localCol, String rowDof, String colDof,
                           double value, String rawToken, String unit, int ordinal) {
}
