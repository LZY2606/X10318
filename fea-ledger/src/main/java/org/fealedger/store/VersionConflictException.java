package org.fealedger.store;

/**
 * Raised when two users derived work from the same version and the first branch
 * has already become current. The second save is refused; it must be rebased or
 * explicitly merged (a resolvable conflict, never a silent overwrite).
 */
public final class VersionConflictException extends RuntimeException {
    public final String currentVid;
    public final String attemptedParent;

    public VersionConflictException(String currentVid, String attemptedParent) {
        super("branch current pointer moved to " + currentVid
                + "; change was based on " + attemptedParent
                + " - rebase or merge required");
        this.currentVid = currentVid;
        this.attemptedParent = attemptedParent;
    }
}
