package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Builds synthetic {@code FILE_NOTIFY_INFORMATION} buffers matching the layout
 * {@link FileNotifyInformationParser} decodes, with correct DWORD-aligned
 * {@code NextEntryOffset} chaining, so the parser is tested with realistic
 * bytes on any platform. The action is arbitrary (the parser ignores it).
 */
final class FileNotifyBuffers {

	private static final int ACTION_ADDED = 1;

	private FileNotifyBuffers() {
	}

	static byte[] buffer(String... relativePaths) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		for (int index = 0; index < relativePaths.length; index++) {
			out.writeBytes(entry(relativePaths[index], index == relativePaths.length - 1));
		}

		return out.toByteArray();
	}

	private static byte[] entry(String relativePath, boolean last) {
		byte[] name = relativePath.getBytes(StandardCharsets.UTF_16LE);
		int padded = (12 + name.length + 3) & ~3;

		ByteBuffer buffer = ByteBuffer.allocate(padded).order(ByteOrder.LITTLE_ENDIAN);

		buffer.putInt(0, last ? 0 : padded);
		buffer.putInt(4, ACTION_ADDED);
		buffer.putInt(8, name.length);

		System.arraycopy(name, 0, buffer.array(), 12, name.length);

		return buffer.array();
	}
}