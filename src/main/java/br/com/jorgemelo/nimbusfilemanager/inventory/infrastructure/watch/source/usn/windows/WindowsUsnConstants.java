package br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.usn.windows;

/**
 * Win32 control codes, access flags and error codes used by the USN change
 * source (winioctl.h / winbase.h / winerror.h). Isolated here so the native
 * volume/resolver classes read declaratively.
 */
final class WindowsUsnConstants {

	// FSCTL codes (winioctl.h).
	static final int FSCTL_QUERY_USN_JOURNAL = 0x000900f4;
	static final int FSCTL_READ_USN_JOURNAL = 0x000900bb;
	static final int FSCTL_CREATE_USN_JOURNAL = 0x000900e7;

	// CreateFile access / share / disposition / flags.
	static final int GENERIC_READ = 0x80000000;
	static final int GENERIC_WRITE = 0x40000000;
	static final int FILE_SHARE_READ = 0x00000001;
	static final int FILE_SHARE_WRITE = 0x00000002;
	static final int FILE_SHARE_DELETE = 0x00000004;
	static final int FILE_SHARE_ALL = FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE;
	static final int OPEN_EXISTING = 3;
	static final int FILE_FLAG_BACKUP_SEMANTICS = 0x02000000;
	static final int FILE_READ_ATTRIBUTES = 0x00000080;

	// FILE_ID_DESCRIPTOR Type discriminator: FileIdType selects the 8-byte FRN
	// union member.
	static final int FILE_ID_TYPE = 0;

	// GetFinalPathNameByHandle flags and the \\?\ prefix it returns.
	static final int FILE_NAME_NORMALIZED = 0x0;
	static final int VOLUME_NAME_DOS = 0x0;
	static final String LONG_PATH_PREFIX = "\\\\?\\";

	// Journal error codes (winerror.h): cursor can no longer be trusted.
	static final int ERROR_JOURNAL_NOT_ACTIVE = 1179;
	static final int ERROR_JOURNAL_DELETE_IN_PROGRESS = 1178;
	static final int ERROR_JOURNAL_ENTRY_DELETED = 1181;

	private WindowsUsnConstants() {
	}
}