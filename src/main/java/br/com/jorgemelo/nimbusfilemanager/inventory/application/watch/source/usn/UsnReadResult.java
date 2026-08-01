package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

import java.util.Arrays;

/**
 * The outcome of one {@code FSCTL_READ_USN_JOURNAL} call, already split by the
 * native seam: the leading 8-byte USN that the next read should start from,
 * followed by the raw {@code USN_RECORD_V2} bytes (empty when the journal has
 * no more records past the requested USN).
 *
 * @param nextStartUsn the USN to pass to the next read to continue where this
 * one stopped.
 * @param records the concatenated raw record bytes (never null; empty when
 * drained).
 */
public record UsnReadResult(long nextStartUsn, byte[] records) {

	public UsnReadResult {
		if (records == null) {
			records = new byte[0];
		}
	}

	/**
	 * Whether this read returned no records (the journal is drained up to here).
	 */
	public boolean drained() {
		return records.length == 0;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof UsnReadResult(long otherUsn, byte[] otherRecords) && nextStartUsn == otherUsn
				&& Arrays.equals(records, otherRecords);
	}

	@Override
	public int hashCode() {
		return 31 * Long.hashCode(nextStartUsn) + Arrays.hashCode(records);
	}

	@Override
	public String toString() {
		return "UsnReadResult[nextStartUsn=" + nextStartUsn + ", records=" + records.length + " bytes]";
	}
}