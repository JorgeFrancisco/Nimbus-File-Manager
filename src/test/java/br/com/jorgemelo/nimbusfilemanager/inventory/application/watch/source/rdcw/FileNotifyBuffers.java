package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Builds synthetic {@code FILE_NOTIFY_INFORMATION} buffers matching the layout
 * {@link FileNotifyInformationParser} decodes, with correct DWORD-aligned
 * {@code NextEntryOffset} chaining, so the parser is tested with realistic
 * bytes on any platform.
 *
 * <p>
 * The action is written because the buffer is a Win32 structure and a fixture
 * that always says "added" cannot tell whether the field is read - which is the
 * whole question about it. The single-argument form keeps that default; the
 * other names an action per entry.
 */
final class FileNotifyBuffers {

	static final int ACTION_ADDED = 1;
	static final int ACTION_REMOVED = 2;
	static final int ACTION_MODIFIED = 3;
	static final int ACTION_RENAMED_OLD_NAME = 4;
	static final int ACTION_RENAMED_NEW_NAME = 5;

	private FileNotifyBuffers() {
	}

	static byte[] buffer(String... relativePaths) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		for (int index = 0; index < relativePaths.length; index++) {
			out.writeBytes(entry(relativePaths[index], ACTION_ADDED, index + 1L, 0, index == relativePaths.length - 1));
		}

		return out.toByteArray();
	}

	/** One buffer whose entries carry the actions given, paired by position. */
	static byte[] buffer(List<Integer> actions, List<String> relativePaths) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		for (int index = 0; index < relativePaths.size(); index++) {
			out.writeBytes(entry(relativePaths.get(index), actions.get(index), index + 1L, 0,
					index == relativePaths.size() - 1));
		}

		return out.toByteArray();
	}

	/**
	 * One FILE_NOTIFY_EXTENDED_INFORMATION record, written at the offsets the
	 * native structure defines rather than at the ones the parser happens to read.
	 * That distinction is the whole value of the fixture: if it copied the
	 * parser's arithmetic, the two would agree about a layout Windows never sends.
	 *
	 * <pre>
	 * 0   DWORD         NextEntryOffset
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
	 * 80  DWORD         FileNameLength   (bytes, not characters)
	 * 84  WCHAR         FileName[]       (UTF-16LE, relative, back-slashes)
	 * </pre>
	 */
	private static byte[] entry(String relativePath, int action, long fileId, int attributes, boolean last) {
		byte[] name = relativePath.getBytes(StandardCharsets.UTF_16LE);

		// Records are aligned on a four-byte boundary, and NextEntryOffset counts
		// from the start of this record to the start of the next.
		int length = (FILE_NAME + name.length + 3) & ~3;

		ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);

		buffer.putInt(NEXT_ENTRY_OFFSET, last ? 0 : length);
		buffer.putInt(ACTION, action);
		buffer.putInt(FILE_ATTRIBUTES, attributes);
		buffer.putLong(FILE_ID, fileId);
		buffer.putLong(PARENT_FILE_ID, 0L);
		buffer.putInt(FILE_NAME_LENGTH, name.length);

		System.arraycopy(name, 0, buffer.array(), FILE_NAME, name.length);

		return buffer.array();
	}

	private static final int NEXT_ENTRY_OFFSET = 0;
	private static final int ACTION = 4;
	private static final int FILE_ATTRIBUTES = 56;
	private static final int FILE_ID = 64;
	private static final int PARENT_FILE_ID = 72;
	private static final int FILE_NAME_LENGTH = 80;
	private static final int FILE_NAME = 84;

}