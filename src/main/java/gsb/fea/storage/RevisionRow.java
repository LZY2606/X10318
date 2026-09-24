package gsb.fea.storage;

public record RevisionRow(String id, String branchId, String parentRevisionId,
                          String author, String kind, String label,
                          String sourceJson, String sourceHash, String createdAt) {
}
