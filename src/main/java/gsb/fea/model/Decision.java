package gsb.fea.model;

import java.util.List;

/**
 * A human adjudication. Selecting a candidate never overwrites the rejected
 * candidates; they remain queryable. A decision can itself be superseded by a
 * later decision on a new branch, which is recorded rather than erased.
 */
public record Decision(String id, String eventId, String chosenCandidateId,
                       List<String> retainedCandidateIds, String rationale,
                       String author, String createdAt) {
}
