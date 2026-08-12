package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.FileSystemChange;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.Interpretation;

/**
 * Drives the read loop over a {@link UsnVolume}: from the current cursor it
 * keeps reading batches until the journal is drained, parses and interprets
 * each, and advances the cursor. Pure orchestration - the native access is
 * behind {@link UsnVolume} and the change logic behind
 * {@link UsnChangeInterpreter} - so cursor advance, coalescing across batches
 * and the drain loop are all tested with fakes.
 *
 * <p>
 * The cursor ({@link #nextUsn()}) only moves forward as batches are consumed;
 * the owner persists it after a full {@link #poll()} so an interrupted process
 * re-reads from the last fully-processed point. {@link #consumeOverflow()}
 * aggregates every "reconcile needed" signal (directory move, or an explicit
 * {@link #requestReconcile()} from a cursor gap).
 */
public class UsnJournalReader {

	private final UsnVolume volume;
	private final UsnChangeInterpreter interpreter;
	private final int bufferBytes;

	private long nextUsn;
	private boolean overflow;

	UsnJournalReader(UsnVolume volume, UsnChangeInterpreter interpreter, int bufferBytes, long startUsn) {
		this.volume = volume;
		this.interpreter = interpreter;
		this.bufferBytes = bufferBytes;
		this.nextUsn = startUsn;
	}

	/**
	 * The USN the next poll will start from - the value to persist as the cursor.
	 */
	public long nextUsn() {
		return nextUsn;
	}

	/**
	 * Reads (and clears) whether a full reconcile is needed since the last call.
	 */
	public boolean consumeOverflow() {
		boolean happened = overflow;

		overflow = false;

		return happened;
	}

	/**
	 * Flags that the catalog must be reconciled (e.g. the cursor could not catch
	 * up).
	 */
	void requestReconcile() {
		overflow = true;
	}

	/**
	 * Jumps the cursor to {@code usn}, used to resynchronize after a journal gap.
	 */
	void resetTo(long usn) {
		this.nextUsn = usn;
	}

	/**
	 * Drains every pending record from the cursor and returns the distinct changes
	 * observed under the root. Stops when the journal reports no more records or
	 * fails to advance (guarding against a stuck cursor).
	 */
	public List<FileSystemChange> poll() {
		Set<FileSystemChange> changed = new LinkedHashSet<>();
		boolean draining = true;

		while (draining) {
			long previous = nextUsn;

			UsnReadResult result = volume.readRecords(nextUsn, bufferBytes);

			if (result.drained()) {
				nextUsn = result.nextStartUsn();
				draining = false;
			} else {
				Interpretation interpretation = interpreter.interpret(UsnRecordParser.parse(result.records()));

				changed.addAll(interpretation.changes());
				overflow |= interpretation.reconcileNeeded();
				nextUsn = result.nextStartUsn();

				// Stop if the journal did not advance despite returning records, rather than
				// spin; the periodic reconcile still covers whatever was missed.
				draining = nextUsn > previous;
			}
		}

		return new ArrayList<>(changed);
	}
}