package gsb.fea.model;

/**
 * One local-matrix entry lifted into global coordinates: the atomic provenance
 * record. Each nonzero of the global matrix can trace back to the ordered list
 * of these terms. The id is stable across export/delete/re-import because it is
 * composed from physical identifiers, never from a generated rowid.
 */
public record Contribution(String id,
                           String elementOriginalId,
                           String localRowDof,
                           String localColDof,
                           String globalRowKey,
                           String globalColKey,
                           int globalRowIndex,
                           int globalColIndex,
                           String rawValue,
                           double value,
                           String unit,
                           int orderIndex,
                           String sourceHash) {

    public static String makeId(String elementOriginalId, String localRowDof, String localColDof) {
        return "contrib:" + elementOriginalId + ":" + localRowDof + "x" + localColDof;
    }
}
