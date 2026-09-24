package gsb.fea.store;

/** Thrown when two users save from the same base version; both branches are kept. */
public final class ConflictException extends RuntimeException {
    public final String baseVersionId;
    public final String currentVersionId;

    public ConflictException(String baseVersionId, String currentVersionId) {
        super("save conflict: base " + baseVersionId + " but current is " + currentVersionId);
        this.baseVersionId = baseVersionId;
        this.currentVersionId = currentVersionId;
    }
}
