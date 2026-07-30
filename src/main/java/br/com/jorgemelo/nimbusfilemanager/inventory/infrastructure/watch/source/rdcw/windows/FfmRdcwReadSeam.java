package br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.rdcw.windows;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.rdcw.windows.WindowsRdcwConstants.ERROR_IO_INCOMPLETE;
import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.rdcw.windows.WindowsRdcwConstants.FILE_FLAG_BACKUP_SEMANTICS;
import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.rdcw.windows.WindowsRdcwConstants.FILE_FLAG_OVERLAPPED;
import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.rdcw.windows.WindowsRdcwConstants.FILE_LIST_DIRECTORY;
import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.rdcw.windows.WindowsRdcwConstants.FILE_SHARE_ALL;
import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.rdcw.windows.WindowsRdcwConstants.NOTIFY_FILTER;
import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.rdcw.windows.WindowsRdcwConstants.OPEN_EXISTING;
import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.rdcw.windows.WindowsRdcwConstants.OVERLAPPED_SIZE;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw.FileNotifyInformationParser;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw.RdcwReadResult;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw.RdcwReadSeam;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw.RdcwUnavailableException;
import br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.windows.WindowsKernel32;
import lombok.extern.slf4j.Slf4j;

/**
 * The FFM implementation of {@link RdcwReadSeam}: opens a single overlapped
 * directory handle on the root ({@code FILE_LIST_DIRECTORY} - no elevation, no
 * per-subdirectory handle) and drives {@code ReadDirectoryChangesW} with
 * {@code bWatchSubtree=TRUE}. One read is kept armed; each poll checks its
 * completion without blocking, parses the buffer, and re-arms. Unverifiable off
 * Windows - guarded by {@code WindowsChangeSourceSupport} and covered by a
 * conditional integration test.
 *
 * <p>
 * Backed by a single {@link Arena#ofShared() shared arena}: {@code open()},
 * {@code poll()} and {@code close()} run on different threads (the reconfigure
 * / web thread, the single watch thread and the shutdown thread), which a
 * confined arena would reject with {@code WrongThreadException}. The watcher
 * serializes poll and close on its own monitor, so no two of them ever touch
 * the buffers concurrently. The buffer, overlapped block and capture state are
 * allocated once and reused every poll - no per-poll allocation.
 */
@Slf4j
final class FfmRdcwReadSeam implements RdcwReadSeam {

	private final Path root;
	private final Arena arena;
	private final MemorySegment handle;
	private final int bufferBytes;
	private final MemorySegment buffer;
	private final MemorySegment overlapped;
	private final MemorySegment bytesTransferred;
	private final MemorySegment capture;

	private boolean armed;

	private FfmRdcwReadSeam(Path root, Arena arena, MemorySegment handle, int bufferBytes, MemorySegment capture) {
		this.root = root;
		this.arena = arena;
		this.handle = handle;
		this.bufferBytes = bufferBytes;
		this.capture = capture;
		this.buffer = arena.allocate(bufferBytes);
		this.overlapped = arena.allocate(OVERLAPPED_SIZE);
		this.bytesTransferred = arena.allocate(JAVA_INT);
	}

	static FfmRdcwReadSeam open(Path root, int bufferBytes) {
		Arena arena = Arena.ofShared();

		try {
			MemorySegment capture = WindowsKernel32.captureState(arena);
			MemorySegment fileName = WindowsKernel32.wideString(arena, root.toString());
			MemorySegment handle = WindowsKernel32.createFile(fileName, FILE_LIST_DIRECTORY, FILE_SHARE_ALL,
					OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OVERLAPPED, capture);

			if (WindowsKernel32.isInvalidHandle(handle)) {
				throw new RdcwUnavailableException("Could not open directory " + root + " for watching (error "
						+ WindowsKernel32.lastError(capture) + ")");
			}

			FfmRdcwReadSeam seam = new FfmRdcwReadSeam(root, arena, handle, bufferBytes, capture);

			seam.arm();

			return seam;
		} catch (RuntimeException | Error failure) {
			arena.close();

			throw failure;
		}
	}

	private void arm() {
		overlapped.fill((byte) 0);

		armed = WindowsKernel32.readDirectoryChanges(handle, buffer, bufferBytes, true, NOTIFY_FILTER, overlapped,
				capture);

		if (!armed) {
			log.debug("Could not arm ReadDirectoryChangesW on {} (error {})", root, WindowsKernel32.lastError(capture));
		}
	}

	@Override
	public RdcwReadResult poll() {
		if (!armed) {
			arm();

			return new RdcwReadResult(List.of(), false);
		}

		if (!WindowsKernel32.getOverlappedResult(handle, overlapped, bytesTransferred, false, capture)) {
			return afterIncompleteOrError(WindowsKernel32.lastError(capture));
		}

		armed = false;

		int bytes = bytesTransferred.get(JAVA_INT, 0L);

		// A zero-byte completion means the OS overflowed the buffer and discarded the
		// batch: request a reconcile instead of losing the changes silently.
		List<String> paths = bytes == 0 ? List.of()
				: FileNotifyInformationParser.parse(buffer.asSlice(0L, bytes).toArray(JAVA_BYTE));

		arm();

		return new RdcwReadResult(paths, bytes == 0);
	}

	private RdcwReadResult afterIncompleteOrError(int error) {
		if (error == ERROR_IO_INCOMPLETE) {
			return new RdcwReadResult(List.of(), false);
		}

		log.debug("ReadDirectoryChangesW failed on {} (error {}); re-arming and requesting reconcile", root, error);

		armed = false;
		arm();

		return new RdcwReadResult(List.of(), true);
	}

	@Override
	public void close() {
		try {
			WindowsKernel32.cancelIo(handle);

			if (!WindowsKernel32.closeHandle(handle, capture)) {
				log.debug("Could not close directory handle for {} (error {})", root,
						WindowsKernel32.lastError(capture));
			}
		} finally {
			arena.close();
		}
	}
}