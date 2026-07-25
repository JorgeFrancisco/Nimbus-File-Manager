package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Builds synthetic {@code USN_RECORD_V2} byte buffers matching the layout
 * {@link UsnRecordParser} decodes, so the parser, interpreter and reader are
 * tested with realistic bytes on any platform (no NTFS needed).
 */
final class UsnRecordBuffers {

	static final long ATTR_DIRECTORY = 0x10L;
	static final long ATTR_NORMAL = 0x80L;

	private UsnRecordBuffers() {
	}

	static byte[] recordBytes(long usn, long frn, long parentFrn, int reason, long attributes, String name) {
		byte[] nameBytes = name.getBytes(StandardCharsets.UTF_16LE);
		int recordLength = 60 + nameBytes.length;

		ByteBuffer buffer = ByteBuffer.allocate(recordLength).order(ByteOrder.LITTLE_ENDIAN);

		buffer.putInt(0, recordLength);
		buffer.putShort(4, (short) 2);
		buffer.putShort(6, (short) 0);
		buffer.putLong(8, frn);
		buffer.putLong(16, parentFrn);
		buffer.putLong(24, usn);
		buffer.putLong(32, 0L);
		buffer.putInt(40, reason);
		buffer.putInt(44, 0);
		buffer.putInt(48, 0);
		buffer.putInt(52, (int) attributes);
		buffer.putShort(56, (short) nameBytes.length);
		buffer.putShort(58, (short) 60);

		System.arraycopy(nameBytes, 0, buffer.array(), 60, nameBytes.length);

		return buffer.array();
	}

	static byte[] concat(byte[]... records) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		for (byte[] bytes : records) {
			out.writeBytes(bytes);
		}

		return out.toByteArray();
	}

	/**
	 * Overwrites the major-version field to simulate an unsupported record version.
	 */
	static byte[] withMajorVersion(byte[] recordBytes, int major) {
		byte[] copy = recordBytes.clone();

		ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).putShort(4, (short) major);

		return copy;
	}
}