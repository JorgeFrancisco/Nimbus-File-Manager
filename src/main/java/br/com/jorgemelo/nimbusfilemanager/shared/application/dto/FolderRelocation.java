package br.com.jorgemelo.nimbusfilemanager.shared.application.dto;

import java.nio.file.Path;

/**
 * A folder going somewhere else, and with it every catalogued file underneath.
 *
 * <p>
 * A folder is not something the catalog knows - it has no row and no identity.
 * What this describes is therefore an operation on the file system, from which
 * the catalog derives one fact per file it holds under that folder.
 *
 * @param provenance what the mover knew, shared by every fact derived from it.
 * It carries no filesystem identity: a folder's identity is not any of its
 * files', and the door refuses one here rather than quietly recording it
 * against N files it does not describe
 */
public record FolderRelocation(Path oldRoot, Path newRoot, CatalogFactProvenance provenance) {
}