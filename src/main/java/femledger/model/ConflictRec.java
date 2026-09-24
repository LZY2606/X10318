package femledger.model;

import java.util.List;

/**
 * A point where more than one legal resolution exists. All legal candidates are kept; the system
 * never collapses ambiguity into a single answer. A human decision selects one candidate but the
 * rejected candidates remain available.
 */
public final class ConflictRec {
    private final String id;
    private final String kind;
    private final String message;
    private final List<String> objectIds;
    private final List<String> candidateResultIds;
    private final String resolvedResultId;

    public ConflictRec(String id, String kind, String message, List<String> objectIds,
                       List<String> candidateResultIds, String resolvedResultId) {
        this.id = id;
        this.kind = kind;
        this.message = message;
        this.objectIds = List.copyOf(objectIds);
        this.candidateResultIds = List.copyOf(candidateResultIds);
        this.resolvedResultId = resolvedResultId;
    }

    public String id() { return id; }
    public String kind() { return kind; }
    public String message() { return message; }
    public List<String> objectIds() { return objectIds; }
    public List<String> candidateResultIds() { return candidateResultIds; }
    public String resolvedResultId() { return resolvedResultId; }
    public boolean isResolved() { return resolvedResultId != null; }

    public ConflictRec withResolution(String resultId) {
        return new ConflictRec(id, kind, message, objectIds, candidateResultIds, resultId);
    }
}
