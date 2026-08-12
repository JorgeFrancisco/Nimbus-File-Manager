package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw;

/**
 * One decoded {@code FILE_NOTIFY_EXTENDED_INFORMATION} entry, reduced to the
 * fields the interpreter needs. Immutable and native-free so it can be built
 * freely in tests.
 *
 * <p>
 * The plain {@code FILE_NOTIFY_INFORMATION} this replaces carried a name and an
 * action and nothing else, so a rename had to be recovered from the order the
 * entries arrived in and whether an entry was a directory had to be answered by
 * going and looking - which cannot work for something that has just left. The
 * extended form answers both from the notification itself.
 *
 * @param action the {@code FILE_ACTION_*} code (see {@code FileNotifyAction}).
 * @param fileId the object's Windows file id, which both halves of a rename
 * share.
 * @param fileAttributes the entry's {@code FILE_ATTRIBUTE_*} bitmask.
 * @param relativePath the entry's path relative to the watched root, with the
 * back-slash separators Win32 uses.
 */
public record FileNotifyEntry(int action, long fileId, int fileAttributes, String relativePath) {

	private static final int FILE_ATTRIBUTE_DIRECTORY = 0x10;

	/** Whether the changed entry is a directory. */
	public boolean directory() {
		return (fileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
	}
}