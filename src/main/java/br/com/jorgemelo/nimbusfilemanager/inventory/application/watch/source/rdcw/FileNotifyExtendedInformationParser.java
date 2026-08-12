package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the raw {@code FILE_NOTIFY_EXTENDED_INFORMATION} bytes returned by
 * {@code ReadDirectoryChangesExW} into the changed entries, relative to the
 * watched root. Pure and native-free: it only decodes a little-endian byte
 * buffer, so it is fully unit-tested with synthetic buffers on any platform.
 *
 * <p>
 * {@code FILE_NOTIFY_EXTENDED_INFORMATION} layout (winnt.h), little-endian:
 *
 * <pre>
 * 0   DWORD         NextEntryOffset   (0 marks the last entry)
 * 4   DWORD         Action
 * 8   LARGE_INTEGER CreationTime
 * 16  LARGE_INTEGER LastModificationTime
 * 24  LARGE_INTEGER LastChangeTime
 * 32  LARGE_INTEGER LastAccessTime
 * 40  LARGE_INTEGER AllocatedLength
 * 48  LARGE_INTEGER FileSize
 * 56  DWORD         FileAttributes
 * 60  DWORD         ReparsePointTag
 * 64  LARGE_INTEGER FileId
 * 72  LARGE_INTEGER ParentFileId
 * 80  DWORD         FileNameLength    (bytes)
 * 84  WCHAR         FileName[...]      (UTF-16, relative, back-slashes)
 * </pre>
 *
 * <p>
 * Only three of those fields are decoded, because only three have a reader. The
 * times and sizes are real and free, and will be worth taking when something
 * asks them a question; carrying them before then would be fields nobody reads.
 */
public final class FileNotifyExtendedInformationParser {

	private static final int OFF_NEXT_ENTRY = 0;
	private static final int OFF_ACTION = 4;
	private static final int OFF_FILE_ATTRIBUTES = 56;
	private static final int OFF_FILE_ID = 64;
	private static final int OFF_FILE_NAME_LENGTH = 80;
	private static final int OFF_FILE_NAME = 84;

	private FileNotifyExtendedInformationParser() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	/** @return the changed entries, in buffer order. */
	public static List<FileNotifyEntry> parse(byte[] notifications) {
		List<FileNotifyEntry> entries = new ArrayList<>();

		if (notifications == null || notifications.length < OFF_FILE_NAME) {
			return entries;
		}

		ByteBuffer buffer = ByteBuffer.wrap(notifications).order(ByteOrder.LITTLE_ENDIAN);

		int offset = 0;

		while (offset >= 0 && offset + OFF_FILE_NAME <= notifications.length) {
			String name = readName(notifications, offset + OFF_FILE_NAME,
					buffer.getInt(offset + OFF_FILE_NAME_LENGTH));

			if (!name.isEmpty()) {
				entries.add(new FileNotifyEntry(buffer.getInt(offset + OFF_ACTION),
						buffer.getLong(offset + OFF_FILE_ID), buffer.getInt(offset + OFF_FILE_ATTRIBUTES), name));
			}

			int next = buffer.getInt(offset + OFF_NEXT_ENTRY);

			offset = next == 0 ? -1 : offset + next;
		}

		return entries;
	}

	private static String readName(byte[] notifications, int start, int length) {
		if (length <= 0 || start < 0 || start + length > notifications.length) {
			return "";
		}

		return new String(notifications, start, length, StandardCharsets.UTF_16LE);
	}
}