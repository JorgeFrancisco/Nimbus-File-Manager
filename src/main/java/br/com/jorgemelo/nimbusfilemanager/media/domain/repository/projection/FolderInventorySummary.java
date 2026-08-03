package br.com.jorgemelo.nimbusfilemanager.media.domain.repository.projection;

/**
 * What the catalog knows about one folder: how many inventoried files sit under
 * it, how many distinct folders those files live in, and how many bytes they
 * add up to. Answered by a single query, which is why the properties dialog can
 * describe a folder without walking the disk - a walk over a drive root would
 * take far longer than a dialog may wait, and would still only be a snapshot.
 *
 * <p>
 * These are the <b>inventoried</b> numbers, not everything the folder holds:
 * anything not yet catalogued is invisible here, and the dialog labels them as
 * such rather than passing them off as a disk total.
 */
public interface FolderInventorySummary {

	long getFileCount();

	long getFolderCount();

	Long getSizeBytes();
}