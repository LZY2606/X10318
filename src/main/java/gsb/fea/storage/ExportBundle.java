package gsb.fea.storage;

import gsb.fea.model.AssemblyResult;
import gsb.fea.model.CaseInput;
import gsb.fea.model.Decision;

import java.util.List;
import java.util.Map;

/**
 * The complete logical dump. Every identifier is composed from physical /
 * content identity (never a database rowid), so importing this bundle into a
 * fresh database preserves ids, ordering and provenance relationships.
 */
public record ExportBundle(String schemaVersion,
                           Map<String, String> meta,
                           List<BranchRow> branches,
                           List<RevisionRow> revisions,
                           List<VersionRow> versions,
                           List<EventRow> events,
                           List<Decision> decisions,
                           List<ExperimentRow> experiments) {
}
