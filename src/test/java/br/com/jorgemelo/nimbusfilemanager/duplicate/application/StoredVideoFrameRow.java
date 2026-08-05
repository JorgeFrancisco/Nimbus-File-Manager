package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.VideoFrameRow;

/**
 * A {@link VideoFrameRow} the in-memory library can return, since an interface
 * projection has no constructor of its own.
 */
record StoredVideoFrameRow(Long catalogFileId, Integer sampleIndex, byte[] hashBytes, byte[] sampleBytes)
		implements VideoFrameRow {

	@Override
	public Long getCatalogFileId() {
		return catalogFileId;
	}

	@Override
	public Integer getSampleIndex() {
		return sampleIndex;
	}

	@Override
	public byte[] getHashBytes() {
		return hashBytes;
	}

	@Override
	public byte[] getSampleBytes() {
		return sampleBytes;
	}
}