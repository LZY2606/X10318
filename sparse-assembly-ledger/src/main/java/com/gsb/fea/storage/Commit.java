package com.gsb.fea.storage;

/** One immutable version. status is STAGED while the object rows are being
 *  written and COMPLETE after the digest check; the current pointer only ever
 *  references COMPLETE commits. */
public record Commit(String commitId, String parentCommitId, String branch, String label,
                     String createdAt, String eventRef, String basisRevision,
                     String digest, String status) {
    public static final String STAGED = "STAGED";
    public static final String COMPLETE = "COMPLETE";
}
