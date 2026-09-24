package com.gsb.fea.storage;

/** A versioned stored object: stable identity (urn, stableId), display order
 *  (ord), payload JSON, fingerprint and origin source commit. */
public record StoredObject(String commitId, String urn, String kind, String stableId,
                           int ord, String json, String fingerprint, String sourceCommitId) {
}
