package gsb.fea.assembly;

import java.util.ArrayList;
import java.util.List;

/** Human-readable, source-linked trace of how one boundary constraint changed the system. */
public final class ConstraintEffect {
    public String constraintUid;
    public String globalDofId;
    public int physicalIndex;
    public String kind;
    public String strategy;
    public String prescribedRaw;
    public List<String> modifiedEntryUids = new ArrayList<>();
    public List<String> removedDofIds = new ArrayList<>();
}
