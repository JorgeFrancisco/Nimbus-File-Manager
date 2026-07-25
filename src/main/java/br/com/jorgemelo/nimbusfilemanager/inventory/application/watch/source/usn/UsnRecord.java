package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

/**
 * One parsed {@code USN_RECORD_V2} entry, reduced to the fields the interpreter
 * needs. Immutable and native-free so it can be built freely in tests.
 *
 * @param usn                        this record's USN (position in the
 *                                   journal).
 * @param fileReferenceNumber        the changed entry's NTFS file reference
 *                                   number (FRN).
 * @param parentFileReferenceNumber  the FRN of the containing directory - the
 *                                   key used to resolve the change's folder.
 * @param reason                     the {@code USN_REASON_*} bitmask (see
 *                                   {@link UsnReason}).
 * @param fileAttributes             the entry's {@code FILE_ATTRIBUTE_*}
 *                                   bitmask.
 * @param fileName                   the entry's own name (not a full path).
 */
public record UsnRecord(long usn, long fileReferenceNumber, long parentFileReferenceNumber, int reason,
		long fileAttributes, String fileName) {

	private static final long FILE_ATTRIBUTE_DIRECTORY = 0x10L;

	/** Whether the changed entry is a directory. */
	public boolean directory() {
		return (fileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
	}
}