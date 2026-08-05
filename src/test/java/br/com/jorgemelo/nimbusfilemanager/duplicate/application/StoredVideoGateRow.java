package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.VideoGateRow;

/**
 * A {@link VideoGateRow} the in-memory library can return, since an interface
 * projection has no constructor of its own.
 */
record StoredVideoGateRow(Long getCatalogFileId, Double getDurationSeconds, Integer getDisplayWidth,
		Integer getDisplayHeight) implements VideoGateRow {

	@Override
	public Long getCatalogFileId() {
		return getCatalogFileId;
	}

	@Override
	public Double getDurationSeconds() {
		return getDurationSeconds;
	}

	@Override
	public Integer getDisplayWidth() {
		return getDisplayWidth;
	}

	@Override
	public Integer getDisplayHeight() {
		return getDisplayHeight;
	}
}