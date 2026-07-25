package br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.usn.windows;

import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.usn.windows.WindowsUsnConstants.FILE_FLAG_BACKUP_SEMANTICS;
import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.usn.windows.WindowsUsnConstants.FILE_ID_TYPE;
import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.usn.windows.WindowsUsnConstants.FILE_NAME_NORMALIZED;
import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.usn.windows.WindowsUsnConstants.FILE_READ_ATTRIBUTES;
import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.usn.windows.WindowsUsnConstants.FILE_SHARE_ALL;
import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.usn.windows.WindowsUsnConstants.LONG_PATH_PREFIX;
import static br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.usn.windows.WindowsUsnConstants.VOLUME_NAME_DOS;
import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;
import java.nio.file.Path;
import java.util.Optional;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn.UsnPathResolver;
import br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.windows.WindowsKernel32;

/**
 * The FFM implementation of {@link UsnPathResolver}: opens a directory by its
 * FRN with {@code OpenFileById} (using the volume handle as the hint) and reads
 * its path back with {@code GetFinalPathNameByHandleW}. Returns empty when the
 * FRN can no longer be opened (e.g. the directory was deleted) - the
 * interpreter then treats the change as outside the tree and the periodic
 * reconcile is the safety net. Shares (never closes) the volume handle owned by
 * {@link FfmUsnVolume}, and uses a short-lived confined arena per resolution.
 */
final class FfmUsnPathResolver implements UsnPathResolver {

	private static final int PATH_BUFFER_CHARS = 520;

	// FILE_ID_DESCRIPTOR: dwSize(4) + Type(4) + union{ LARGE_INTEGER FileId;
	// FILE_ID_128; } = 24 bytes.
	private static final StructLayout FILE_ID_DESCRIPTOR = MemoryLayout.structLayout(JAVA_INT.withName("dwSize"),
			JAVA_INT.withName("Type"), JAVA_LONG.withName("FileId"), MemoryLayout.paddingLayout(8));
	private static final VarHandle DESCRIPTOR_SIZE = FILE_ID_DESCRIPTOR.varHandle(groupElement("dwSize"));
	private static final VarHandle DESCRIPTOR_TYPE = FILE_ID_DESCRIPTOR.varHandle(groupElement("Type"));
	private static final VarHandle DESCRIPTOR_FILE_ID = FILE_ID_DESCRIPTOR.varHandle(groupElement("FileId"));

	private final MemorySegment volumeHandle;

	FfmUsnPathResolver(MemorySegment volumeHandle) {
		this.volumeHandle = volumeHandle;
	}

	@Override
	public Optional<Path> resolveDirectory(long fileReferenceNumber) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment descriptor = arena.allocate(FILE_ID_DESCRIPTOR);

			DESCRIPTOR_SIZE.set(descriptor, 0L, (int) FILE_ID_DESCRIPTOR.byteSize());
			DESCRIPTOR_TYPE.set(descriptor, 0L, FILE_ID_TYPE);
			DESCRIPTOR_FILE_ID.set(descriptor, 0L, fileReferenceNumber);

			MemorySegment directory = WindowsKernel32.openFileById(volumeHandle, descriptor, FILE_READ_ATTRIBUTES,
					FILE_SHARE_ALL, FILE_FLAG_BACKUP_SEMANTICS);

			if (WindowsKernel32.isInvalidHandle(directory)) {
				return Optional.empty();
			}

			try {
				return readFinalPath(arena, directory);
			} finally {
				WindowsKernel32.closeHandle(directory, WindowsKernel32.captureState(arena));
			}
		}
	}

	private Optional<Path> readFinalPath(Arena arena, MemorySegment directory) {
		MemorySegment buffer = arena.allocate(JAVA_CHAR, PATH_BUFFER_CHARS);

		int length = WindowsKernel32.getFinalPathNameByHandle(directory, buffer, PATH_BUFFER_CHARS,
				FILE_NAME_NORMALIZED | VOLUME_NAME_DOS);

		if (length <= 0 || length >= PATH_BUFFER_CHARS) {
			return Optional.empty();
		}

		String path = new String(buffer.toArray(JAVA_CHAR), 0, length);

		if (path.startsWith(LONG_PATH_PREFIX)) {
			path = path.substring(LONG_PATH_PREFIX.length());
		}

		return Optional.of(Path.of(path));
	}
}