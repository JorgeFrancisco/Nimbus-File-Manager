package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection;

/**
 * One photo's 32x32 luminance sample, fetched after the distance scan has
 * decided which photos are worth comparing properly.
 *
 * <p>
 * Separate from {@link PhotoHashRow} because the two are wanted at different
 * moments and in wildly different quantities: every candidate needs a hash, and
 * on a real library under one pair in a thousand survives to need a sample.
 */
public interface PhotoSampleRow {

	Long getCatalogFileId();

	byte[] getSampleBytes();
}