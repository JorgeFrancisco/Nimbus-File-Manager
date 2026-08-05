package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoSampleRow;

/**
 * One photo's luminance sample as the second load reads it, for tests that need
 * the projection without needing a database.
 */
final class StoredPhotoSample implements PhotoSampleRow {

	private final long catalogFileId;
	private final byte[] sampleBytes;

	StoredPhotoSample(long catalogFileId, byte[] sampleBytes) {
		this.catalogFileId = catalogFileId;
		this.sampleBytes = sampleBytes;
	}

	@Override
	public Long getCatalogFileId() {
		return catalogFileId;
	}

	@Override
	public byte[] getSampleBytes() {
		return sampleBytes;
	}
}