package gsb.fea.storage;

/** Raised when two users save edits derived from the same base revision. */
public class ConflictException extends RuntimeException {
    public final String currentRevisionId;

    public ConflictException(String message, String currentRevisionId) {
        super(message);
        this.currentRevisionId = currentRevisionId;
    }
}
