package br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection;

/**
 * A catalogued file under a folder, and the place it is at.
 *
 * <p>
 * The path travels with the identifier because a capability that is about to
 * move the whole folder has to say, before it moves anything, where each file
 * it is responsible for will end up - and it cannot say that from an identifier
 * alone.
 */
public interface FolderPlacementRow {

	Long getCatalogFileId();

	String getCurrentPath();
}