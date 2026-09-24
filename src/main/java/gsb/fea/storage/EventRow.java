package gsb.fea.storage;

public record EventRow(String id, String branchId, String revisionId, String versionId,
                       String parentEventId, String type, String payloadJson,
                       String author, String createdAt, int seq) {
}
