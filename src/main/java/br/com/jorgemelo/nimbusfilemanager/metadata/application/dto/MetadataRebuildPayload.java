package br.com.jorgemelo.nimbusfilemanager.metadata.application.dto;

import java.time.LocalDateTime;
import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MetadataRebuildField;

/**
 * What a queued metadata rebuild carries.
 *
 * <p>
 * Unlike a fingerprint backlog, this one is not a query with no arguments: the
 * folder, the fields, the cutoff and the dry-run flag are the request, and two
 * requests differing in any of them are two different things to ask for. They
 * travel in the row because the process that runs them is not the one that read
 * the form.
 *
 * @param notAnalysedSince the moment the previous run started, which is how
 * "continue where it stopped" is expressed: files this run rebuilds are stamped
 * later, so the next one skips them. Carried rather than recomputed, because
 * recomputing it at claim time would move the mark by however long the row
 * waited in the queue
 */
public record MetadataRebuildPayload(Integer schemaVersion, String sourcePath, List<MetadataRebuildField> refresh,
		Boolean dryRun, LocalDateTime notAnalysedSince) {

	public boolean dryRunValue() {
		return dryRun != null && dryRun;
	}

	public MetadataRebuildRequest toRequest() {
		return MetadataRebuildRequest.forFolder(sourcePath, refresh, dryRunValue(), notAnalysedSince);
	}
}