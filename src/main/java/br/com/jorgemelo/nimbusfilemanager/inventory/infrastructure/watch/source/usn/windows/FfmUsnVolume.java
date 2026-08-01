package br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.usn.windows;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.usn.windows.WindowsUsnConstants.ERROR_JOURNAL_DELETE_IN_PROGRESS;
import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.usn.windows.WindowsUsnConstants.ERROR_JOURNAL_ENTRY_DELETED;
import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.usn.windows.WindowsUsnConstants.ERROR_JOURNAL_NOT_ACTIVE;
import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.usn.windows.WindowsUsnConstants.FILE_SHARE_ALL;
import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.usn.windows.WindowsUsnConstants.FSCTL_CREATE_USN_JOURNAL;
import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.usn.windows.WindowsUsnConstants.FSCTL_QUERY_USN_JOURNAL;
import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.usn.windows.WindowsUsnConstants.FSCTL_READ_USN_JOURNAL;
import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.usn.windows.WindowsUsnConstants.GENERIC_READ;
import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.usn.windows.WindowsUsnConstants.GENERIC_WRITE;
import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.usn.windows.WindowsUsnConstants.OPEN_EXISTING;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn.UsnGapException;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn.UsnReadResult;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn.UsnUnavailableException;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn.UsnVolume;
import br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.windows.WindowsKernel32;
import lombok.extern.slf4j.Slf4j;

/**
 * The FFM implementation of {@link UsnVolume}: opens the NTFS volume device
 * ({@code \\.\C:}), queries (creating if needed) its USN journal, and reads
 * record batches. The Win32 structures are described with {@link MemoryLayout}
 * and read through named {@link VarHandle}s instead of scattered raw offsets,
 * keeping the ABI explicit and free of union/field-order surprises.
 * Unverifiable off Windows - guarded by {@code WindowsUsnSupport} and covered
 * by conditional NTFS integration tests.
 *
 * <p>
 * The volume handle is a bare pointer with no arena of its own; each native
 * call uses a short-lived {@link Arena#ofConfined() confined arena} for its
 * scratch buffers, freed as soon as the call returns. The whole volume
 * lifecycle (open, read, close) runs synchronously on one thread, so
 * confinement holds and nothing accumulates across the many reads of a
 * catch-up.
 */
@Slf4j
final class FfmUsnVolume implements UsnVolume {

	private static final int USN_JOURNAL_DATA_BYTES = 80;
	private static final int USN_HEADER_BYTES = 8;
	private static final long DEFAULT_MAX_SIZE = 32L * 1024 * 1024;
	private static final long DEFAULT_ALLOCATION_DELTA = 1L * 1024 * 1024;
	private static final int ALL_REASONS = 0xFFFFFFFF;
	private static final long OUTPUT_ALIGNMENT = 8L;

	// USN_JOURNAL_DATA_V2 prefix (the device may write up to 80 bytes; only these
	// fields are read).
	private static final StructLayout USN_JOURNAL_DATA = MemoryLayout.structLayout(JAVA_LONG.withName("UsnJournalID"),
			JAVA_LONG.withName("FirstUsn"), JAVA_LONG.withName("NextUsn"), JAVA_LONG.withName("LowestValidUsn"));
	private static final VarHandle JOURNAL_ID = USN_JOURNAL_DATA.varHandle(groupElement("UsnJournalID"));
	private static final VarHandle NEXT_USN = USN_JOURNAL_DATA.varHandle(groupElement("NextUsn"));
	private static final VarHandle LOWEST_VALID_USN = USN_JOURNAL_DATA.varHandle(groupElement("LowestValidUsn"));

	// READ_USN_JOURNAL_DATA_V0 (40 bytes).
	private static final StructLayout READ_USN_JOURNAL_DATA = MemoryLayout.structLayout(JAVA_LONG.withName("StartUsn"),
			JAVA_INT.withName("ReasonMask"), JAVA_INT.withName("ReturnOnlyOnClose"), JAVA_LONG.withName("Timeout"),
			JAVA_LONG.withName("BytesToWaitFor"), JAVA_LONG.withName("UsnJournalID"));
	private static final VarHandle READ_START_USN = READ_USN_JOURNAL_DATA.varHandle(groupElement("StartUsn"));
	private static final VarHandle READ_REASON_MASK = READ_USN_JOURNAL_DATA.varHandle(groupElement("ReasonMask"));
	private static final VarHandle READ_JOURNAL_ID = READ_USN_JOURNAL_DATA.varHandle(groupElement("UsnJournalID"));

	// CREATE_USN_JOURNAL_DATA (16 bytes).
	private static final StructLayout CREATE_USN_JOURNAL_DATA = MemoryLayout
			.structLayout(JAVA_LONG.withName("MaximumSize"), JAVA_LONG.withName("AllocationDelta"));
	private static final VarHandle CREATE_MAX_SIZE = CREATE_USN_JOURNAL_DATA.varHandle(groupElement("MaximumSize"));
	private static final VarHandle CREATE_ALLOCATION_DELTA = CREATE_USN_JOURNAL_DATA
			.varHandle(groupElement("AllocationDelta"));

	private final MemorySegment handle;
	private final String volumePath;

	private long journalId;
	private long nextUsn;
	private long lowestValidUsn;
	private int lastQueryError;

	private FfmUsnVolume(MemorySegment handle, String volumePath) {
		this.handle = handle;
		this.volumePath = volumePath;
	}

	/**
	 * Opens the volume that hosts {@code driveLetter} (e.g. {@code "C"}) and its
	 * journal.
	 */
	static FfmUsnVolume open(String driveLetter) {
		String volumePath = "\\\\.\\" + driveLetter + ":";

		MemorySegment handle = openVolumeHandle(volumePath);

		FfmUsnVolume volume = new FfmUsnVolume(handle, volumePath);

		volume.queryOrCreateJournal();

		return volume;
	}

	private static MemorySegment openVolumeHandle(String volumePath) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment capture = WindowsKernel32.captureState(arena);
			MemorySegment name = WindowsKernel32.wideString(arena, volumePath);

			MemorySegment opened = WindowsKernel32.createFile(name, GENERIC_READ | GENERIC_WRITE, FILE_SHARE_ALL,
					OPEN_EXISTING, 0, capture);

			if (WindowsKernel32.isInvalidHandle(opened)) {
				// No write access (journal creation impossible): still fine for query/read.
				opened = WindowsKernel32.createFile(name, GENERIC_READ, FILE_SHARE_ALL, OPEN_EXISTING, 0, capture);
			}

			if (WindowsKernel32.isInvalidHandle(opened)) {
				throw new UsnUnavailableException(
						"Could not open volume " + volumePath + " (error " + WindowsKernel32.lastError(capture) + ")");
			}

			return opened;
		}
	}

	private void queryOrCreateJournal() {
		if (!queryJournal()) {
			int error = lastQueryError;

			if (error != ERROR_JOURNAL_NOT_ACTIVE) {
				throw new UsnUnavailableException(
						"Could not query USN journal on " + volumePath + " (error " + error + ")");
			}

			createJournal();

			if (!queryJournal()) {
				throw new UsnUnavailableException("USN journal still unavailable after creation on " + volumePath
						+ " (error " + lastQueryError + ")");
			}
		}
	}

	private boolean queryJournal() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment capture = WindowsKernel32.captureState(arena);
			MemorySegment out = arena.allocate(USN_JOURNAL_DATA_BYTES, OUTPUT_ALIGNMENT);
			MemorySegment returned = arena.allocate(JAVA_INT);

			boolean ok = WindowsKernel32.deviceIoControl(handle, FSCTL_QUERY_USN_JOURNAL, MemorySegment.NULL, 0, out,
					USN_JOURNAL_DATA_BYTES, returned, capture);

			if (!ok) {
				lastQueryError = WindowsKernel32.lastError(capture);

				return false;
			}

			journalId = (long) JOURNAL_ID.get(out, 0L);
			lowestValidUsn = (long) LOWEST_VALID_USN.get(out, 0L);
			nextUsn = (long) NEXT_USN.get(out, 0L);

			return true;
		}
	}

	private void createJournal() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment capture = WindowsKernel32.captureState(arena);
			MemorySegment in = arena.allocate(CREATE_USN_JOURNAL_DATA);

			CREATE_MAX_SIZE.set(in, 0L, DEFAULT_MAX_SIZE);
			CREATE_ALLOCATION_DELTA.set(in, 0L, DEFAULT_ALLOCATION_DELTA);

			MemorySegment returned = arena.allocate(JAVA_INT);

			boolean ok = WindowsKernel32.deviceIoControl(handle, FSCTL_CREATE_USN_JOURNAL, in,
					(int) CREATE_USN_JOURNAL_DATA.byteSize(), MemorySegment.NULL, 0, returned, capture);

			if (!ok) {
				throw new UsnUnavailableException("Could not create USN journal on " + volumePath + " (error "
						+ WindowsKernel32.lastError(capture) + ")");
			}
		}
	}

	/**
	 * The shared volume handle, reused by {@link FfmUsnPathResolver} (which never
	 * closes it).
	 */
	MemorySegment nativeHandle() {
		return handle;
	}

	@Override
	public long journalId() {
		return journalId;
	}

	@Override
	public long nextUsn() {
		return nextUsn;
	}

	@Override
	public long lowestValidUsn() {
		return lowestValidUsn;
	}

	@Override
	public UsnReadResult readRecords(long fromUsn, int bufferBytes) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment capture = WindowsKernel32.captureState(arena);
			MemorySegment in = arena.allocate(READ_USN_JOURNAL_DATA);

			READ_START_USN.set(in, 0L, fromUsn);
			READ_REASON_MASK.set(in, 0L, ALL_REASONS);
			READ_JOURNAL_ID.set(in, 0L, journalId);

			MemorySegment out = arena.allocate(bufferBytes, OUTPUT_ALIGNMENT);
			MemorySegment returned = arena.allocate(JAVA_INT);

			boolean ok = WindowsKernel32.deviceIoControl(handle, FSCTL_READ_USN_JOURNAL, in,
					(int) READ_USN_JOURNAL_DATA.byteSize(), out, bufferBytes, returned, capture);

			if (!ok) {
				throw readFailure(WindowsKernel32.lastError(capture));
			}

			int bytes = returned.get(JAVA_INT, 0L);
			long nextStartUsn = out.get(JAVA_LONG, 0L);

			if (bytes <= USN_HEADER_BYTES) {
				return new UsnReadResult(nextStartUsn, new byte[0]);
			}

			return new UsnReadResult(nextStartUsn,
					out.asSlice(USN_HEADER_BYTES, bytes - USN_HEADER_BYTES).toArray(JAVA_BYTE));
		}
	}

	private RuntimeException readFailure(int error) {
		if (error == ERROR_JOURNAL_NOT_ACTIVE || error == ERROR_JOURNAL_DELETE_IN_PROGRESS
				|| error == ERROR_JOURNAL_ENTRY_DELETED) {
			return new UsnGapException("USN journal cursor invalidated on " + volumePath + " (error " + error + ")");
		}

		return new UsnUnavailableException("Could not read USN journal on " + volumePath + " (error " + error + ")");
	}

	@Override
	public void close() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment capture = WindowsKernel32.captureState(arena);

			if (!WindowsKernel32.closeHandle(handle, capture)) {
				log.debug("Could not close volume handle for {} (error {})", volumePath,
						WindowsKernel32.lastError(capture));
			}
		}
	}
}