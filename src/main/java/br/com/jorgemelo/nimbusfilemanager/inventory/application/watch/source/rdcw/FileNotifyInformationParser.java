package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the raw {@code FILE_NOTIFY_INFORMATION} bytes returned by
 * {@code ReadDirectoryChangesW} into the changed entries' paths, relative to
 * the watched root. Pure and native-free: it only decodes a little-endian byte
 * buffer, so it is fully unit-tested with synthetic buffers on any platform.
 *
 * <p>
 * {@code FILE_NOTIFY_INFORMATION} layout (winnt.h), little-endian:
 *
 * <pre>
 * 0   DWORD NextEntryOffset   (0 marks the last entry)
 * 4   DWORD Action            (added/removed/modified/renamed-old/renamed-new)
 * 8   DWORD FileNameLength    (bytes)
 * 12  WCHAR FileName[...]      (UTF-16, path relative to the root, back-slashes)
 * </pre>
 *
 * <p>
 * The action is read off the wire but intentionally not surfaced, because no
 * decision downstream would differ if it were. Every action - create, modify,
 * delete and both sides of a rename - becomes one changed path, and the single
 * answer to any of them is the debounced pair the watcher asks for: a reconcile
 * to retire what left, and an inventory to catalogue what arrived. A rename
 * therefore yields both its paths, whichever of the two lies inside the
 * library, and a folder yields its own path, which is the only notice the files
 * that came with it ever produce.
 */
public final class FileNotifyInformationParser {

	private static final int OFF_NEXT_ENTRY = 0;
	private static final int OFF_FILE_NAME_LENGTH = 8;
	private static final int OFF_FILE_NAME = 12;

	private FileNotifyInformationParser() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	/** @return the changed entries' paths relative to the root, in buffer order. */
	public static List<String> parse(byte[] notifications) {
		List<String> paths = new ArrayList<>();

		if (notifications == null || notifications.length < OFF_FILE_NAME) {
			return paths;
		}

		ByteBuffer buffer = ByteBuffer.wrap(notifications).order(ByteOrder.LITTLE_ENDIAN);

		int offset = 0;

		while (offset >= 0 && offset + OFF_FILE_NAME <= notifications.length) {
			int nameLength = buffer.getInt(offset + OFF_FILE_NAME_LENGTH);

			String name = readName(notifications, offset + OFF_FILE_NAME, nameLength);

			if (!name.isEmpty()) {
				paths.add(name);
			}

			int next = buffer.getInt(offset + OFF_NEXT_ENTRY);

			offset = next == 0 ? -1 : offset + next;
		}

		return paths;
	}

	private static String readName(byte[] notifications, int start, int length) {
		if (length <= 0 || start < 0 || start + length > notifications.length) {
			return "";
		}

		return new String(notifications, start, length, StandardCharsets.UTF_16LE);
	}
}