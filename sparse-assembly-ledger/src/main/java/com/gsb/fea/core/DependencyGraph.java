package com.gsb.fea.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dependency graph linking source objects to derived objects. Every node has a
 * content fingerprint. When a direct source changes, fingerprints are recomputed
 * and only downstream nodes whose inputs actually changed become dirty.
 *
 * Edges point from dependency (input) to dependent (output).
 */
public final class DependencyGraph {

    public enum Status { VALID, DIRTY }

    public static final class Node {
        public final String urn;
        public final String kind;
        String fingerprint;
        Status status = Status.VALID;
        String lastReason = "computed";
        final List<Node> deps = new ArrayList<>();
        final List<Node> dependents = new ArrayList<>();

        Node(String urn, String kind, String fingerprint) {
            this.urn = urn; this.kind = kind; this.fingerprint = fingerprint;
        }

        public String urn() { return urn; }
        public String kind() { return kind; }
        public String fingerprint() { return fingerprint; }
        public Status status() { return status; }
        public String lastReason() { return lastReason; }
        public List<String> dependencyUrns() { return deps.stream().map(n -> n.urn).toList(); }
        public List<String> dependentUrns() { return dependents.stream().map(n -> n.urn).toList(); }
    }

    private final Map<String, Node> nodes = new LinkedHashMap<>();

    public Node getOrCreate(String urn, String kind, String fingerprint) {
        Node n = nodes.get(urn);
        if (n == null) {
            n = new Node(urn, kind, fingerprint);
            nodes.put(urn, n);
        }
        return n;
    }

    public Node node(String urn) { return nodes.get(urn); }
    public List<Node> all() { return List.copyOf(nodes.values()); }

    public void dependsOn(String dependentUrn, String dependencyUrn) {
        Node dep = nodes.get(dependencyUrn);
        Node out = nodes.get(dependentUrn);
        if (dep == null || out == null) {
            throw new IllegalArgumentException("unknown edge: " + dependencyUrn + " -> " + dependentUrn);
        }
        if (!out.deps.contains(dep)) {
            out.deps.add(dep);
            dep.dependents.add(out);
        }
    }

    /** Update one direct source fingerprint; returns urns that must be recomputed. */
    public List<String> sourceChanged(String urn, String newFingerprint, String reason) {
        Node n = nodes.get(urn);
        if (n == null) throw new IllegalArgumentException("unknown source node: " + urn);
        if (java.util.Objects.equals(n.fingerprint, newFingerprint)) {
            return List.of();
        }
        n.fingerprint = newFingerprint;
        n.status = Status.DIRTY;
        n.lastReason = reason;
        List<String> dirty = new ArrayList<>();
        Deque<Node> queue = new ArrayDeque<>(n.dependents);
        Set<String> seen = new java.util.HashSet<>();
        while (!queue.isEmpty()) {
            Node cur = queue.poll();
            if (!seen.add(cur.urn)) continue;
            cur.status = Status.DIRTY;
            cur.lastReason = "upstream changed: " + reason + " (via " + urn + ")";
            dirty.add(cur.urn);
            queue.addAll(cur.dependents);
        }
        return dirty;
    }

    /** Called after recomputation: fresh inputs -> VALID, still-dirty input stays DIRTY. */
    public void recomputed(String urn, String newFingerprint) {
        Node n = nodes.get(urn);
        if (n == null) throw new IllegalArgumentException("unknown node: " + urn);
        n.fingerprint = newFingerprint;
        boolean anyDirty = n.deps.stream().anyMatch(d -> d.status == Status.DIRTY);
        n.status = anyDirty ? Status.DIRTY : Status.VALID;
        n.lastReason = anyDirty ? "recomputed but an input is still dirty"
                                : "recomputed from unchanged/valid inputs";
    }

    /** Human/machine readable explanation of why a result is valid or must be redone. */
    public Map<String, Object> explain(String urn) {
        Node n = nodes.get(urn);
        if (n == null) throw new IllegalArgumentException("unknown node: " + urn);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("urn", n.urn);
        out.put("kind", n.kind);
        out.put("status", n.status.name());
        out.put("reason", n.lastReason);
        List<String> changed = new ArrayList<>();
        List<String> same = new ArrayList<>();
        for (Node d : n.deps) (d.status == Status.DIRTY ? changed : same).add(d.urn);
        out.put("changedInputs", changed);
        out.put("unchangedInputs", same);
        return out;
    }
}
