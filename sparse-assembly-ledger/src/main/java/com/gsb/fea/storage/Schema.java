package com.gsb.fea.storage;

/** Database schema definition and old-version migration (v1 -> v2). */
final class Schema {
    static final int CURRENT_VERSION = 2;

    static final String V1_OBJECTS = """
            CREATE TABLE object(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              commit_id TEXT NOT NULL,
              urn TEXT NOT NULL,
              kind TEXT NOT NULL,
              stable_id TEXT NOT NULL,
              ord INTEGER NOT NULL,
              json TEXT NOT NULL,
              fingerprint TEXT NOT NULL,
              UNIQUE(commit_id, urn)
            );
            """;

    static final String V2_OBJECTS = """
            CREATE TABLE object(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              commit_id TEXT NOT NULL,
              urn TEXT NOT NULL,
              kind TEXT NOT NULL,
              stable_id TEXT NOT NULL,
              ord INTEGER NOT NULL,
              json TEXT NOT NULL,
              fingerprint TEXT NOT NULL,
              source_commit_id TEXT,
              UNIQUE(commit_id, urn)
            );
            """;

    static final String COMMITS = """
            CREATE TABLE commit_t(
              commit_id TEXT PRIMARY KEY,
              parent_commit_id TEXT,
              branch TEXT NOT NULL,
              label TEXT NOT NULL,
              created_at TEXT NOT NULL,
              event_ref TEXT,
              basis_revision TEXT,
              digest TEXT NOT NULL,
              status TEXT NOT NULL
            );
            """;

    static final String EVENTS = """
            CREATE TABLE event(
              seq INTEGER PRIMARY KEY AUTOINCREMENT,
              event_id TEXT NOT NULL UNIQUE,
              commit_id TEXT,
              at TEXT NOT NULL,
              actor TEXT NOT NULL,
              kind TEXT NOT NULL,
              branch TEXT,
              json TEXT NOT NULL
            );
            """;

    static final String REF = """
            CREATE TABLE current_ref(
              singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
              commit_id TEXT,
              updated_at TEXT NOT NULL
            );
            """;

    static final String META = """
            CREATE TABLE meta(
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL
            );
            """;

    private Schema() {}
}
