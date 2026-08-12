package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

import java.time.Instant;

/**
 * One parsed {@code USN_RECORD_V2} entry, reduced to the fields the interpreter
 * needs. Immutable and native-free so it can be built freely in tests.
 *
 * @param usn this record's USN (position in the journal).
 * @param fileReferenceNumber the changed entry's NTFS file reference number
 * (FRN).
 * @param parentFileReferenceNumber the FRN of the containing directory - the
 * key used to resolve the change's folder.
 * @param reason the {@code USN_REASON_*} bitmask (see {@link UsnReason}).
 * @param fileAttributes the entry's {@code FILE_ATTRIBUTE_*} bitmask.
 * @param fileName the entry's own name (not a full path).
 * @param timestamp when NTFS added the record to the journal, as a Win32
 * {@code FILETIME}. Raw here rather than converted, so the parser stays a
 * decoder and the one place that knows what the number means is
 * {@link #occurredAt()}.
 */
public record UsnRecord(long usn, long fileReferenceNumber, long parentFileReferenceNumber, int reason,
		long fileAttributes, String fileName, long timestamp) {

	private static final long FILE_ATTRIBUTE_DIRECTORY = 0x10L;

	/**
	 * 100-nanosecond intervals between the {@code FILETIME} epoch (1601-01-01) and
	 * the Unix one, which is what the conversion has to subtract.
	 */
	private static final long FILETIME_EPOCH_OFFSET = 116_444_736_000_000_000L;

	private static final long TICKS_PER_SECOND = 10_000_000L;
	private static final long NANOS_PER_TICK = 100L;

	/** Whether the changed entry is a directory. */
	public boolean directory() {
		return (fileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
	}

	/**
	 * When the change actually happened, according to NTFS - or null when the
	 * record carries no time.
	 *
	 * <p>
	 * This is the one thing the journal knows that a live notification does not,
	 * and it is the whole reason for reading it: the replay recovers a window the
	 * application was down for, which can be days, so a fact stamped with the
	 * moment of recovery would say a Monday change happened on Friday.
	 *
	 * <p>
	 * A {@code FILETIME} counts 100-nanosecond intervals from 1601 in UTC, so the
	 * conversion is arithmetic and never touches a time zone. Floor division
	 * rather than plain division because the value is signed and a date before
	 * 1970 would otherwise round towards zero and land a tick late.
	 */
	public Instant occurredAt() {
		if (timestamp == 0L) {
			return null;
		}

		long ticks = timestamp - FILETIME_EPOCH_OFFSET;

		return Instant.ofEpochSecond(Math.floorDiv(ticks, TICKS_PER_SECOND),
				Math.floorMod(ticks, TICKS_PER_SECOND) * NANOS_PER_TICK);
	}
}