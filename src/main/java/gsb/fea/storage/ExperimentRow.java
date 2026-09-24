package gsb.fea.storage;

public record ExperimentRow(String id, String scenario, String baselineVersionId,
                            String directSourceChanged, String scopeJson,
                            String acceptedVersionId, String rejectedVersionId,
                            String eventsJson, String createdAt) {
}
