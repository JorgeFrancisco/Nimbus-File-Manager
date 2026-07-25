package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

import java.io.Closeable;

/**
 * The native seam over an opened NTFS volume and its USN Change Journal. Every
 * Win32 call ({@code CreateFile} on the volume,
 * {@code FSCTL_QUERY_USN_JOURNAL}, {@code FSCTL_READ_USN_JOURNAL}) lives behind
 * this interface, so the reader and interpreter above it are pure Java tested
 * with a fake volume - only the JNA implementation is platform-specific and
 * unverifiable off Windows.
 */
public interface UsnVolume extends Closeable {

	/**
	 * The current journal's identifier. A different value on restart means the
	 * journal was deleted/recreated and any persisted cursor is invalid.
	 */
	long journalId();

	/** The next USN the journal will assign - i.e. the end of the journal now. */
	long nextUsn();

	/**
	 * The oldest USN still retained. A persisted cursor below this has aged out and
	 * cannot be caught up from (full reconcile required).
	 */
	long lowestValidUsn();

	/**
	 * Reads the next batch of records at or after {@code fromUsn}, filling up to
	 * {@code bufferBytes}. The returned {@link UsnReadResult#nextStartUsn()} is
	 * where the following read must continue; a drained result carries no records.
	 */
	UsnReadResult readRecords(long fromUsn, int bufferBytes);

	@Override
	void close();
}