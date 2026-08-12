package br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums;

/**
 * Which mechanism observed a change.
 *
 * <p>
 * Carried because the three do not know the same things: one of them knows the
 * object's identity, two of them can pair a rename, and one can do neither. A
 * consumer deciding how far to trust a change - and how much work to do to make
 * up for what is missing - has to be able to ask where it came from.
 */
public enum FileChangeSourceKind {

	/**
	 * The NTFS USN change journal. Replayed once at startup to recover the window
	 * the application was down for; it is not the live source.
	 */
	USN_JOURNAL,

	/** {@code ReadDirectoryChangesW}: the live source on Windows. */
	READ_DIRECTORY_CHANGES,

	/** The portable Java {@code WatchService}, used everywhere else. */
	WATCH_SERVICE
}