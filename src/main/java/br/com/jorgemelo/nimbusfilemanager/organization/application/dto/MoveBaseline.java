package br.com.jorgemelo.nimbusfilemanager.organization.application.dto;

/**
 * What a file was made of, measured from the file itself.
 *
 * <p>
 * Captured from the source immediately before a move and then proved against
 * the target after it, which is why one record serves as both the baseline and
 * the result: a move that returns has already shown the two to be identical, so
 * carrying a second digest would only offer a caller the chance to compare
 * values that cannot differ.
 *
 * <p>
 * Measured, not remembered. The catalog's own digest may be older than the file
 * - something changed it between the last inventory and now - so this is read
 * from the bytes every time and never taken from a row.
 */
public record MoveBaseline(long sizeBytes, String sha256) {
}