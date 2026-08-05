package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection;

/**
 * One sampled frame of a video, with no file metadata attached.
 *
 * <p>
 * The comparison needs the hash and the sample; the file name, path and size
 * belong to a published group, which an incremental run does not build for the
 * videos it merely compares against. This is what an arrival reads for the
 * videos a surviving pair actually named.
 *
 * <p>
 * An interface projection rather than a record because the payload is two
 * arrays: a record carrying them would owe an {@code equals} nothing here ever
 * calls.
 */
public interface VideoFrameRow {

	Long getCatalogFileId();

	Integer getSampleIndex();

	byte[] getHashBytes();

	byte[] getSampleBytes();
}