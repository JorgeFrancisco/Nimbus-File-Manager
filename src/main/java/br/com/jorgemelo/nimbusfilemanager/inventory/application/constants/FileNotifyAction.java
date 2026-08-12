package br.com.jorgemelo.nimbusfilemanager.inventory.application.constants;

/**
 * The {@code FILE_ACTION_*} codes {@code ReadDirectoryChangesExW} reports in
 * each entry. Unlike the USN reason field these are an enumeration, not a
 * bitmask: one entry carries exactly one action.
 *
 * <p>
 * Values are the documented Win32 constants (winnt.h). Kept away from the
 * native glue so the parser and interpreter that read them stay pure and are
 * unit-tested on any platform.
 */
public final class FileNotifyAction {

	public static final int ADDED = 0x0000_0001;
	public static final int REMOVED = 0x0000_0002;
	public static final int MODIFIED = 0x0000_0003;
	public static final int RENAMED_OLD_NAME = 0x0000_0004;
	public static final int RENAMED_NEW_NAME = 0x0000_0005;

	private FileNotifyAction() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}
}