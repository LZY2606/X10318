package gsb.fea.storage;

public record VersionRow(String id, String revisionId, String branchId,
                         String candidatesJson, String adoptedCandidateId,
                         String status, String createdAt) {
}
