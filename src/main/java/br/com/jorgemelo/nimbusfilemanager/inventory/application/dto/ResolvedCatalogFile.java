package br.com.jorgemelo.nimbusfilemanager.inventory.application.dto;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;

/**
 * The entry a scan decided on, and what deciding it did.
 *
 * @param reactivated the entry was missing or removed and this scan brought it
 * back. Carried out rather than acted on here: a file rejoining the catalog
 * rejoins what a duplicate analysis may look at, and only the pass that
 * catalogued it can say so once for all of them
 * @param created nothing was known about this file before
 */
public record ResolvedCatalogFile(CatalogFile entity, boolean reactivated, boolean created) {
}