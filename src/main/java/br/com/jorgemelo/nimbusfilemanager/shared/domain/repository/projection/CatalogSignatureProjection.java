package br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection;

import java.time.LocalDateTime;

/**
 * The catalog under a folder, reduced to what tells whether it moved: how many
 * files are there and when one of them last changed.
 */
public interface CatalogSignatureProjection {

	long getFileCount();

	LocalDateTime getLatestUpdate();
}