package org.gsb.fem.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One complete derived assembly version. Identity of every derived object is
 * stable and content-addressable. {@code complete=false} means ERROR-level
 * diagnostics were produced and the current pointer must not switch to it.
 */
public class AssemblyResult {
    public String id;
    public String snapshotId;
    public String snapshotHash;
    public String dofMapId;
    public String constraintSetId;
    public String renumberId;
    public String floatSpecId;
    public String summation;
    public String strategy;
    public String contentHash;
    public boolean complete;

    public List<GlobalEntry> entries = new ArrayList<>();
    public List<ConstraintEffect> constraintEffects = new ArrayList<>();
    public List<Diagnostic> diagnostics = new ArrayList<>();
    public List<String> allDofIds = new ArrayList<>();
    public Map<String, Integer> indexOf = new LinkedHashMap<>();
    public List<String> isolatedDofs = new ArrayList<>();

    public boolean hasErrors() {
        for (Diagnostic d : diagnostics) {
            if ("ERROR".equals(d.severity)) {
                return true;
            }
        }
        return false;
    }

    public GlobalEntry entry(String id) {
        for (GlobalEntry e : entries) {
            if (e.id.equals(id)) {
                return e;
            }
        }
        return null;
    }
}
