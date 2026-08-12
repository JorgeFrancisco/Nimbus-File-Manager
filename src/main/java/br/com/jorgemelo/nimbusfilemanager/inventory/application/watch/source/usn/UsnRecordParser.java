package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the raw {@code USN_RECORD_V2} bytes returned by
 * {@code FSCTL_READ_USN_JOURNAL} into {@link UsnRecord}s. Pure and native-free:
 * it only decodes a little-endian byte buffer, so it is fully unit-tested with
 * synthetic buffers on any platform.
 *
 * <p>
 * {@code USN_RECORD_V2} layout (winioctl.h), little-endian:
 *
 * <pre>
 * 0   DWORD     RecordLength
 * 4   WORD      MajorVersion
 * 6   WORD      MinorVersion
 * 8   DWORDLONG FileReferenceNumber
 * 16  DWORDLONG ParentFileReferenceNumber
 * 24  USN       Usn (LONGLONG)
 * 32  LARGE_INTEGER TimeStamp
 * 40  DWORD     Reason
 * 44  DWORD     SourceInfo
 * 48  DWORD     SecurityId
 * 52  DWORD     FileAttributes
 * 56  WORD      FileNameLength (bytes)
 * 58  WORD      FileNameOffset (bytes, from record start)
 * 60  WCHAR     FileName[...]
 * </pre>
 */
public final class UsnRecordParser {

	private static final int MAJOR_VERSION_V2 = 2;
	private static final int OFF_RECORD_LENGTH = 0;
	private static final int OFF_MAJOR_VERSION = 4;
	private static final int OFF_FILE_REFERENCE = 8;
	private static final int OFF_PARENT_REFERENCE = 16;
	private static final int OFF_USN = 24;
	private static final int OFF_TIMESTAMP = 32;
	private static final int OFF_REASON = 40;
	private static final int OFF_FILE_ATTRIBUTES = 52;
	private static final int OFF_FILE_NAME_LENGTH = 56;
	private static final int OFF_FILE_NAME_OFFSET = 58;
	private static final int HEADER_MIN_BYTES = 60;

	private UsnRecordParser() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	/**
	 * Decodes every {@code USN_RECORD_V2} in {@code records}. Stops cleanly at the
	 * first truncated or malformed entry (a short final record from a partially
	 * filled buffer) instead of throwing, returning whatever parsed so far. Records
	 * of an unexpected major version are skipped by their own length.
	 */
	public static List<UsnRecord> parse(byte[] records) {
		List<UsnRecord> parsed = new ArrayList<>();

		if (records == null || records.length < HEADER_MIN_BYTES) {
			return parsed;
		}

		ByteBuffer buffer = ByteBuffer.wrap(records).order(ByteOrder.LITTLE_ENDIAN);

		int offset = 0;

		while (offset + HEADER_MIN_BYTES <= records.length) {
			int recordLength = buffer.getInt(offset + OFF_RECORD_LENGTH);

			if (recordLength < HEADER_MIN_BYTES || offset + recordLength > records.length) {
				break;
			}

			if (buffer.getShort(offset + OFF_MAJOR_VERSION) == MAJOR_VERSION_V2) {
				parsed.add(readRecord(records, buffer, offset));
			}

			offset += recordLength;
		}

		return parsed;
	}

	private static UsnRecord readRecord(byte[] records, ByteBuffer buffer, int offset) {
		long fileReference = buffer.getLong(offset + OFF_FILE_REFERENCE);
		long parentReference = buffer.getLong(offset + OFF_PARENT_REFERENCE);
		long usn = buffer.getLong(offset + OFF_USN);
		long timestamp = buffer.getLong(offset + OFF_TIMESTAMP);
		int reason = buffer.getInt(offset + OFF_REASON);
		long attributes = Integer.toUnsignedLong(buffer.getInt(offset + OFF_FILE_ATTRIBUTES));
		int nameLength = Short.toUnsignedInt(buffer.getShort(offset + OFF_FILE_NAME_LENGTH));
		int nameOffset = Short.toUnsignedInt(buffer.getShort(offset + OFF_FILE_NAME_OFFSET));

		String fileName = readName(records, offset + nameOffset, nameLength);

		return new UsnRecord(usn, fileReference, parentReference, reason, attributes, fileName, timestamp);
	}

	private static String readName(byte[] records, int start, int length) {
		if (length <= 0 || start < 0 || start + length > records.length) {
			return "";
		}

		return new String(records, start, length, StandardCharsets.UTF_16LE);
	}
}