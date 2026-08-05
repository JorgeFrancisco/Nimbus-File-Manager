package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoHashRow;

/**
 * One photo's hash as the incremental load reads it, for tests that need the
 * projection without needing a database.
 */
final class StoredPhotoHash implements PhotoHashRow {

	private final long catalogFileId;
	private final byte[] hashBytes;

	StoredPhotoHash(long catalogFileId, byte[] hashBytes) {
		this.catalogFileId = catalogFileId;
		this.hashBytes = hashBytes;
	}

	@Override
	public Long getCatalogFileId() {
		return catalogFileId;
	}

	@Override
	public byte[] getHashBytes() {
		return hashBytes;
	}
}