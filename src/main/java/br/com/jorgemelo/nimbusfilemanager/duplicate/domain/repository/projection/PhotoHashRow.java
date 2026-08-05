package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection;

/**
 * One photo's perceptual hash, and nothing else.
 *
 * <p>
 * The distance scan needs 32 bytes per photo; the luminance sample beside it in
 * the same row is 1024. Reading both for a library is 126 MB against under 4,
 * and an incremental run compares one arriving photo against everything - so
 * the samples it would load are, almost entirely, samples of pairs the distance
 * filter is about to reject. They are fetched afterwards, for the survivors
 * only.
 *
 * <p>
 * An interface projection rather than a record because the payload is an array:
 * a record carrying one would owe an {@code equals} nothing here ever calls.
 */
public interface PhotoHashRow {

	Long getCatalogFileId();

	byte[] getHashBytes();
}