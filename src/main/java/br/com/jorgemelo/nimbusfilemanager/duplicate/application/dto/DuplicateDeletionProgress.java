package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

/**
 * Snapshot of a background Duplicados deletion for the screen to poll: while
 * {@code running} is true it drives a "Movendo X de N" bar from
 * {@code processed}/{@code total}; once it turns false {@code result} carries
 * the final {@link DuplicateDeletionResult} (null while still running).
 */
public record DuplicateDeletionProgress(boolean running, int processed, int total, double percent,
		DuplicateDeletionResult result) {
}