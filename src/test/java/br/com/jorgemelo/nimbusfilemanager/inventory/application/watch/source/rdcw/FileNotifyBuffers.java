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
			out.writeBytes(entry(relativePaths[index], ACTION_ADDED, index == relativePaths.length - 1));
		}

		return out.toByteArray();
	}

	/** One buffer whose entries carry the actions given, paired by position. */
	static byte[] buffer(List<Integer> actions, List<String> relativePaths) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		for (int index = 0; index < relativePaths.size(); index++) {
			out.writeBytes(entry(relativePaths.get(index), actions.get(index),
					index == relativePaths.size() - 1));
		}

		return out.toByteArray();
	}

	private static byte[] entry(String relativePath, int action, boolean last) {
		byte[] name = relativePath.getBytes(StandardCharsets.UTF_16LE);
		int padded = (12 + name.length + 3) & ~3;

		ByteBuffer buffer = ByteBuffer.allocate(padded).order(ByteOrder.LITTLE_ENDIAN);

		buffer.putInt(0, last ? 0 : padded);
		buffer.putInt(4, action);
		buffer.putInt(8, name.length);

		System.arraycopy(name, 0, buffer.array(), 12, name.length);

		return buffer.array();
	}
}