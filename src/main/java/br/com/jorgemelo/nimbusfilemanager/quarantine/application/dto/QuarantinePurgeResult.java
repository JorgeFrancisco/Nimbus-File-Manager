package br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto;

/**
 * Outcome of one retention-purge run over the quarantine folder.
 *
 * @param scanned how many overdue items were examined this run.
 * @param purged files physically removed and their movement records cleaned.
 * @param catalogsFreed media files whose catalog row was also removed (no other
 * movement referenced them).
 * @param skipped items left untouched because their record changed between the
 * listing and the delete (restored or already gone).
 * @param busy items another operation was holding: a conversion moving
 * originals into quarantine locks the whole folder, and the user has to be told
 * that rather than shown a delete that quietly did nothing.
 * @param errors items whose physical delete failed; their record is kept and
 * retried next run.
 */
public record QuarantinePurgeResult(int scanned, int purged, int catalogsFreed, int skipped, int busy, int errors) {
}