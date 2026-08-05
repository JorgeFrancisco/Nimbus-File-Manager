package br.com.jorgemelo.nimbusfilemanager.backup.application.constants;

import java.util.Set;

/**
 * What a backup archive of this product contains: two entries, named here
 * because three places now have to agree on them - the packing, the check that
 * the packing worked, and the restore that reads them back.
 *
 * <p>
 * A closed set rather than a convention. The format admits exactly these two
 * names, so "is this entry expected?" is answered by a whitelist, which also
 * settles every question a zip normally raises about paths: a name that is not
 * one of these two is refused whether it is a folder, a nested path, an
 * absolute path or a traversal.
 */
public final class BackupEntries {

	public static final String DUMP = "catalog.dump";

	public static final String MANIFEST = "manifest.json";

	/** Every entry a valid archive has, and the only ones it may have. */
	public static final Set<String> NAMES = Set.of(DUMP, MANIFEST);

	private BackupEntries() {
	}
}