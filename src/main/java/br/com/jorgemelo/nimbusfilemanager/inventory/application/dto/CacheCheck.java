package br.com.jorgemelo.nimbusfilemanager.inventory.application.dto;

import java.util.List;
import java.util.Set;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentSuspect;

/**
 * What one batch's cache check found: which files need no work, and which ones
 * stopped looking settled.
 *
 * <p>
 * Two answers from one read because they come from the same rows, and carrying
 * the second one out as data is the point. The check runs in a read-only
 * transaction - it is a lookup, and holding write locks over a query the whole
 * walk repeats would be wrong - so the request the suspects deserve cannot be
 * made from inside it. PostgreSQL says so plainly: {@code cannot execute INSERT
 * in a read-only transaction}, which is how an inventory over an already
 * catalogued library died thirty seconds in.
 *
 * @param settledKeys the catalogued files this batch may skip
 * @param suspects the catalogued files whose size or timestamp no longer match
 * what the catalog records, for the caller to ask about once it is somewhere it
 * may write
 */
public record CacheCheck(Set<String> settledKeys, List<ContentSuspect> suspects) {

	public static CacheCheck nothingSettled() {
		return new CacheCheck(Set.of(), List.of());
	}
}