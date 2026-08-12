package br.com.jorgemelo.nimbusfilemanager.shared.application.dto;

/**
 * Where the file ended up, read back from the row the change wrote.
 *
 * <p>
 * Read back rather than echoed: {@code pathKey} and {@code currentFolder} are
 * computed by the database from the path, and a caller that needs either of
 * them - to decide whether a duplicate analysis has to be told about this - must
 * get the value the database arrived at rather than one Java worked out to
 * match.
 *
 * @param replayed the change had already been recorded under the same event
 * identity and nothing was written this time. The paths are still the ones on
 * record, so a caller can carry on without caring which attempt did the work
 */
public record AppliedLocationChange(long eventId, String currentPath, String pathKey, String currentFolder,
		boolean replayed) {
}