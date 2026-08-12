package br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.rdcw.windows;

/**
 * Win32 flags and codes for {@code ReadDirectoryChangesW} (winnt.h /
 * winbase.h). Isolated so the native seam reads declaratively.
 */
final class WindowsRdcwConstants {

	static final int FILE_LIST_DIRECTORY = 0x0001;
	static final int FILE_SHARE_READ = 0x00000001;
	static final int FILE_SHARE_WRITE = 0x00000002;
	static final int FILE_SHARE_DELETE = 0x00000004;
	static final int FILE_SHARE_ALL = FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE;
	static final int OPEN_EXISTING = 3;
	static final int FILE_FLAG_BACKUP_SEMANTICS = 0x02000000;
	static final int FILE_FLAG_OVERLAPPED = 0x40000000;

	// Change filter: names (files and directories), size and last-write cover
	// create/rename/delete/modify. The debounced full reconcile does the rest.
	static final int FILE_NOTIFY_CHANGE_FILE_NAME = 0x00000001;
	static final int FILE_NOTIFY_CHANGE_DIR_NAME = 0x00000002;
	static final int FILE_NOTIFY_CHANGE_SIZE = 0x00000008;
	static final int FILE_NOTIFY_CHANGE_LAST_WRITE = 0x00000010;
	static final int NOTIFY_FILTER = FILE_NOTIFY_CHANGE_FILE_NAME | FILE_NOTIFY_CHANGE_DIR_NAME
			| FILE_NOTIFY_CHANGE_SIZE | FILE_NOTIFY_CHANGE_LAST_WRITE;
	/**
	 * {@code ReadDirectoryNotifyExtendedInformation} of
	 * {@code READ_DIRECTORY_NOTIFY_INFORMATION_CLASS} - the shape that carries the
	 * file id and the attributes, rather than a name and an action alone.
	 */
	static final int EXTENDED_INFORMATION = 2;

	// OVERLAPPED is 32 bytes on 64-bit; zeroed (hEvent=NULL) so completion signals
	// the file handle, which GetOverlappedResult polls without waiting.
	static final int OVERLAPPED_SIZE = 32;

	static final int ERROR_IO_INCOMPLETE = 996;

	private WindowsRdcwConstants() {
	}
}