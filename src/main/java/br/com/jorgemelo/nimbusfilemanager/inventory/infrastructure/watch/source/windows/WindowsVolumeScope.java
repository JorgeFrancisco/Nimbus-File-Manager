package br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.windows;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

/**
 * Names the volume a watched root lives on, durably.
 *
 * <p>
 * A Windows file id is unique within a volume and repeats on the next one, so a
 * stored identity is only comparable if what it is scoped to is stored with it.
 * The drive letter is the obvious answer and the wrong one: letters are
 * assignments, not identity, and the external drive that is {@code E:} today is
 * {@code F:} after another one is plugged in first - which would silently make
 * yesterday's identities incomparable with today's, or worse, comparable with
 * the wrong volume's.
 *
 * <p>
 * The volume GUID path ({@code \\?\Volume{...}\}) is the mount manager's own
 * name for the volume: it survives reboots, drive-letter reassignment and
 * remounting, and changes only when the volume itself is recreated - at which
 * point the files it named are gone anyway.
 *
 * <p>
 * Two calls, not one, and asked once per watched root rather than per file. The
 * mount point is resolved first because a volume can be mounted on a folder
 * rather than a letter, and a library sitting on one would otherwise be scoped
 * to the volume of the drive root above it - two different volumes answering to
 * one name, which is the exact failure this class exists to prevent.
 */
@Slf4j
public final class WindowsVolumeScope {

	private static final int MOUNT_POINT_CHARS = 260;
	private static final int VOLUME_NAME_CHARS = 64;
	private static final int WIDE_CHAR_BYTES = 2;

	private WindowsVolumeScope() {
	}

	/**
	 * @return the volume GUID path hosting {@code root}, or empty when Windows
	 * will not name it - in which case no identity is reported at all, which is
	 * the honest answer rather than one that cannot be compared.
	 */
	public static Optional<String> forRoot(Path root) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment capture = WindowsKernel32.captureState(arena);
			MemorySegment path = WindowsKernel32.wideString(arena, root.toAbsolutePath().toString());
			MemorySegment mountPoint = arena.allocate((long) MOUNT_POINT_CHARS * WIDE_CHAR_BYTES);

			if (!WindowsKernel32.getVolumePathName(path, mountPoint, MOUNT_POINT_CHARS, capture)) {
				log.info("Could not resolve the mount point of {} (error {}); changes there carry no identity", root,
						WindowsKernel32.lastError(capture));

				return Optional.empty();
			}

			MemorySegment volumeName = arena.allocate((long) VOLUME_NAME_CHARS * WIDE_CHAR_BYTES);

			if (!WindowsKernel32.getVolumeNameForVolumeMountPoint(mountPoint, volumeName, VOLUME_NAME_CHARS, capture)) {
				log.info("Could not name the volume of {} (error {}); changes there carry no identity", root,
						WindowsKernel32.lastError(capture));

				return Optional.empty();
			}

			return Optional.of(volumeName.getString(0L, StandardCharsets.UTF_16LE));
		}
	}
}