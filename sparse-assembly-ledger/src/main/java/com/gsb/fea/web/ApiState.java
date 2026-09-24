package com.gsb.fea.web;

import com.gsb.fea.casepkg.ReplayEngine;
import com.gsb.fea.storage.Repository;

import java.nio.file.Path;

/** Shared, synchronized server state. All writes go through the repository. */
public final class ApiState {
    private final Repository repo;
    private final ReplayEngine engine;
    private boolean replayed;

    public ApiState(Path dbFile) {
        this.repo = new Repository(dbFile);
        this.engine = new ReplayEngine(repo);
        this.engine.loadDefault();
    }

    public synchronized Repository repo() { return repo; }
    public synchronized ReplayEngine engine() { return engine; }

    public synchronized boolean isReplayed() { return replayed; }

    public synchronized void ensureReplayed() {
        if (!replayed && repo.commits().isEmpty()) {
            engine.replayAll();
        }
        replayed = true;
    }

    public synchronized void close() { repo.close(); }
}
