package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw;

import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.constants.FileNotifyAction;

/**
 * Notifications as {@code ReadDirectoryChangesExW} delivers them, for the tests
 * that drive the source through its seam.
 *
 * <p>
 * The identity is part of the notification and not decoration: it is what pairs
 * the two halves of a rename, and Windows supplies it with the extended
 * information. A test that leaves it at zero is describing a source that cannot
 * pair anything, which is a real case and should be written on purpose rather
 * than by omission.
 */
final class Notifications {

	private static final int DIRECTORY = 0x10;

	static FileNotifyEntry added(String relativePath) {
		return new FileNotifyEntry(FileNotifyAction.ADDED, 0L, 0, relativePath);
	}

	static FileNotifyEntry modified(String relativePath) {
		return new FileNotifyEntry(FileNotifyAction.MODIFIED, 0L, 0, relativePath);
	}

	static FileNotifyEntry removed(String relativePath) {
		return new FileNotifyEntry(FileNotifyAction.REMOVED, 0L, 0, relativePath);
	}

	/** The two halves Windows sends for a rename, tied by the object's own id. */
	static List<FileNotifyEntry> renamed(long fileId, String from, String to) {
		return List.of(new FileNotifyEntry(FileNotifyAction.RENAMED_OLD_NAME, fileId, 0, from),
				new FileNotifyEntry(FileNotifyAction.RENAMED_NEW_NAME, fileId, 0, to));
	}

	static List<FileNotifyEntry> renamedDirectory(long fileId, String from, String to) {
		return List.of(new FileNotifyEntry(FileNotifyAction.RENAMED_OLD_NAME, fileId, DIRECTORY, from),
				new FileNotifyEntry(FileNotifyAction.RENAMED_NEW_NAME, fileId, DIRECTORY, to));
	}

	static RdcwReadResult read(FileNotifyEntry... entries) {
		return new RdcwReadResult(List.of(entries), false);
	}

	static RdcwReadResult read(List<FileNotifyEntry> entries) {
		return new RdcwReadResult(entries, false);
	}

	static RdcwReadResult overflowed() {
		return new RdcwReadResult(List.of(), true);
	}

	private Notifications() {
	}
}