package com.gsb.fea.web;

import com.gsb.fea.casepkg.CaseFile;
import com.gsb.fea.casepkg.CaseStep;
import com.gsb.fea.storage.Archive;
import com.gsb.fea.storage.Commit;
import com.gsb.fea.storage.Repository;
import com.gsb.fea.util.Json;

import io.javalin.Javalin;
import io.javalin.http.Context;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Javalin + SVG review application. */
public final class WebServer {
    private final ApiState state;

    public WebServer(ApiState state) {
        this.state = state;
    }

    public Javalin start(String host, int port) {
        Javalin app = Javalin.create(cfg -> cfg.http.defaultContentType = "application/json");
        app.get("/", ctx -> serveStatic(ctx, "index.html", "text/html; charset=utf-8"));
        app.get("/app.js", ctx -> serveStatic(ctx, "app.js", "application/javascript; charset=utf-8"));
        app.get("/style.css", ctx -> serveStatic(ctx, "style.css", "text/css; charset=utf-8"));

        app.get("/api/case", this::caseInfo);
        app.post("/api/replay", this::replay);
        app.get("/api/commits", this::commits);
        app.get("/api/commits/{commit}", this::commitOverview);
        app.get("/api/commits/{commit}/mesh", this::mesh);
        app.get("/api/commits/{commit}/sparsity", this::sparsity);
        app.get("/api/commits/{commit}/diagnostics", this::diagnostics);
        app.get("/api/commits/{commit}/objects/{urn}", this::object);
        app.get("/api/commits/{commit}/events", this::events);
        app.get("/api/events", this::allEvents);
        app.get("/api/diff", this::diff);
        app.post("/api/review", this::review);
        app.get("/api/export", this::exportArchive);
        app.post("/api/import", this::importArchive);

        return app.start(host, port);
    }

    private void serveStatic(Context ctx, String resource, String contentType) throws Exception {
        try (InputStream in = WebServer.class.getResourceAsStream("/web/" + resource)) {
            if (in == null) { ctx.status(404); return; }
            ctx.contentType(contentType).result(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private String commitParam(Context ctx) {
        String id = ctx.pathParam("commit");
        if ("current".equals(id)) {
            String cur = state.repo().currentCommitId();
            if (cur == null) throw new IllegalStateException("no current commit");
            return cur;
        }
        return id;
    }

    private void caseInfo(Context ctx) {
        CaseFile cf = state.engine().caseFile();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caseId", cf.caseId());
        out.put("title", cf.title());
        out.put("summary", cf.summary());
        out.put("units", cf.units());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (CaseStep s : cf.steps()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id());
            m.put("kind", s.kind());
            m.put("title", s.title());
            m.put("narrative", s.narrative());
            m.put("params", s.params());
            m.put("expectDiagnostics", s.expectDiagnostics());
            m.put("expectState", s.expectState());
            steps.add(m);
        }
        out.put("steps", steps);
        out.put("replayed", state.isReplayed());
        out.put("currentCommitId", state.repo().currentCommitId());
        ctx.json(out);
    }

    private void replay(Context ctx) {
        state.ensureReplayed();
        ctx.json(Map.of("replayed", true, "currentCommitId",
                state.repo().currentCommitId() == null ? "" : state.repo().currentCommitId()));
    }

    private void commits(Context ctx) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Commit c : state.repo().commits()) out.add(Views.commitView(c));
        ctx.json(Map.of("commits", out, "current", state.repo().currentCommitId()));
    }

    private void commitOverview(Context ctx) {
        ctx.json(Views.overview(state.repo(), commitParam(ctx)));
    }

    private void mesh(Context ctx) {
        ctx.json(Views.mesh(state.repo(), commitParam(ctx)));
    }

    private void sparsity(Context ctx) {
        ctx.json(Views.sparsity(state.repo(), commitParam(ctx)));
    }

    private void diagnostics(Context ctx) {
        ctx.json(Views.diagnostics(state.repo(), commitParam(ctx)));
    }

    private void object(Context ctx) {
        String urn = ctx.pathParam("urn");
        Map<String, Object> m = Views.sourceObject(state.repo(), commitParam(ctx), urn);
        if (m == null) ctx.status(404).json(Map.of("error", "not found", "urn", urn));
        else ctx.json(m);
    }

    private void events(Context ctx) {
        ctx.json(Map.of("events", state.repo().events(null)));
    }

    private void allEvents(Context ctx) {
        ctx.json(Map.of("events", state.repo().events(null)));
    }

    private void diff(Context ctx) {
        String left = ctx.queryParam("left");
        String right = ctx.queryParam("right");
        if (left == null || right == null) {
            ctx.status(400).json(Map.of("error", "left and right commit ids required"));
            return;
        }
        ctx.json(Views.diff(state.repo(), left, right));
    }

    /**
     * Accept / reject / adjust decisions. Every review action is a NEW event
     * and (for adjust/accept) a NEW version; rejected candidates stay in the
     * old commits and are never overwritten. Saving on a stale base revision
     * returns HTTP 409 carrying both revisions for resolution.
     */
    @SuppressWarnings("unchecked")
    private void review(Context ctx) {
        Map<String, Object> body = Json.read(ctx.body(), Map.class);
        String action = (String) body.getOrDefault("action", "ACCEPT");
        String baseCommit = (String) body.get("baseCommitId");
        String branch = (String) body.getOrDefault("branch", "review");
        String actor = (String) body.getOrDefault("actor", "reviewer");
        String candidateUrn = (String) body.get("candidateUrn");
        String note = (String) body.getOrDefault("note", "");
        String objectUrn = (String) body.get("objectUrn");
        Object newValue = body.get("newValue");

        Repository repo = state.repo();
        String eventId = "ev-review-" + System.nanoTime();
        try {
            List<Repository.NewObject> objs = new ArrayList<>();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("action", action);
            payload.put("baseCommitId", baseCommit);
            payload.put("candidateUrn", candidateUrn);
            payload.put("objectUrn", objectUrn);
            payload.put("newValue", newValue);
            payload.put("note", note);

            String label = switch (action) {
                case "REJECT" -> "否定候选 " + candidateUrn;
                case "ADJUST" -> "参数调整 " + objectUrn;
                default -> "接受候选 " + candidateUrn;
            };
            if ("ADJUST".equals(action) && objectUrn != null) {
                objs.add(new Repository.NewObject(objectUrn, "review-adjustment",
                        objectUrn, Json.write(Map.of("newValue", newValue, "note", note,
                                "basedOn", baseCommit)),
                        com.gsb.fea.util.Hashing.sha256(Json.write(newValue)), baseCommit));
            }
            Commit created = repo.commit(objs, branch, label, baseCommit, eventId, null);
            payload.put("commitId", created.commitId());
            repo.recordEvent(eventId, created.commitId(), actor, "REVIEW_" + action,
                    branch, payload);
            ctx.status(201).json(payload);
        } catch (com.gsb.fea.storage.ConflictException ce) {
            ctx.status(409).json(Map.of(
                    "error", "CONFLICT_RESOLUTION_REQUIRED",
                    "message", ce.getMessage(),
                    "expectedBase", ce.expectedBase(),
                    "actualCurrent", ce.actualCurrent(),
                    "resolvable", true,
                    "retainedCandidateUrn", candidateUrn == null ? "" : candidateUrn));
        }
    }

    private void exportArchive(Context ctx) throws Exception {
        Path tmp = Files.createTempFile("ledger-export", ".json");
        Archive.exportTo(state.repo(), tmp);
        ctx.contentType("application/json")
           .header("Content-Disposition", "attachment; filename=ledger-archive.json")
           .result(Files.readString(tmp, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private void importArchive(Context ctx) {
        try {
            Map<String, Object> body = ctx.isMultipart() ? null : Json.read(ctx.body(), Map.class);
            Path archive = Files.createTempFile("ledger-import", ".json");
            if (body != null && body.get("archiveJson") instanceof String s) {
                Files.writeString(archive, s, StandardCharsets.UTF_8);
            } else {
                ctx.uploadedFile("archive").stream().findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("archive field required"))
                        .content().transferTo(Files.newOutputStream(archive));
            }
            Path newDb = Files.createTempFile("ledger-reimport", ".db");
            Files.delete(newDb);
            Archive.importFrom(archive, newDb);
            // verify identity/order/provenance against the current repository
            Map<String, Object> verify = ImportCheck.verify(state.repo(), newDb);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("importedDb", newDb.toString());
            out.put("verification", verify);
            ctx.status(201).json(out);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "import failed", "message",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }
}
