package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.constants.UsnReason;

class UsnRecordParserTest {

	@Test
	void decodesEveryFieldOfASingleRecord() {
		byte[] buffer = UsnRecordBuffers.recordBytes(42L, 111L, 222L, UsnReason.FILE_CREATE | UsnReason.CLOSE,
				UsnRecordBuffers.ATTR_NORMAL, "photo.jpg");

		List<UsnRecord> records = UsnRecordParser.parse(buffer);

		Assertions.assertThat(records).singleElement().satisfies(parsed -> {
			Assertions.assertThat(parsed.usn()).isEqualTo(42L);
			Assertions.assertThat(parsed.fileReferenceNumber()).isEqualTo(111L);
			Assertions.assertThat(parsed.parentFileReferenceNumber()).isEqualTo(222L);
			Assertions.assertThat(parsed.fileName()).isEqualTo("photo.jpg");
			Assertions.assertThat(parsed.directory()).isFalse();
			Assertions.assertThat(UsnReason.hasAny(parsed.reason(), UsnReason.FILE_CREATE)).isTrue();
		});
	}

	@Test
	void flagsDirectoryRecordsFromTheAttributes() {
		byte[] buffer = UsnRecordBuffers.recordBytes(1L, 1L, 2L, UsnReason.RENAME_NEW_NAME,
				UsnRecordBuffers.ATTR_DIRECTORY,
				"2024");

		Assertions.assertThat(UsnRecordParser.parse(buffer)).singleElement()
				.satisfies(parsed -> Assertions.assertThat(parsed.directory()).isTrue());
	}

	@Test
	void parsesSeveralConcatenatedRecordsInOrder() {
		byte[] buffer = UsnRecordBuffers.concat(
				UsnRecordBuffers.recordBytes(1L, 10L, 20L, UsnReason.FILE_CREATE, UsnRecordBuffers.ATTR_NORMAL,
						"a.jpg"),
				UsnRecordBuffers.recordBytes(2L, 11L, 20L, UsnReason.FILE_DELETE, UsnRecordBuffers.ATTR_NORMAL,
						"b.png"));

		Assertions.assertThat(UsnRecordParser.parse(buffer)).extracting(UsnRecord::fileName).containsExactly("a.jpg",
				"b.png");
	}

	@Test
	void skipsUnsupportedMajorVersionButKeepsSteppingByLength() {
		byte[] unsupported = UsnRecordBuffers.withMajorVersion(
				UsnRecordBuffers.recordBytes(1L, 10L, 20L, UsnReason.FILE_CREATE, UsnRecordBuffers.ATTR_NORMAL,
						"skip.me"), 3);
		byte[] supported = UsnRecordBuffers.recordBytes(2L, 11L, 20L, UsnReason.FILE_CREATE,
				UsnRecordBuffers.ATTR_NORMAL,
				"keep.jpg");

		Assertions.assertThat(UsnRecordParser.parse(UsnRecordBuffers.concat(unsupported, supported)))
				.extracting(UsnRecord::fileName).containsExactly("keep.jpg");
	}

	@Test
	void stopsCleanlyAtATruncatedTrailingRecord() {
		byte[] valid = UsnRecordBuffers.recordBytes(1L, 10L, 20L, UsnReason.FILE_CREATE, UsnRecordBuffers.ATTR_NORMAL,
				"a.jpg");
		byte[] truncated = UsnRecordBuffers.concat(valid, new byte[] { 5, 0, 0, 0, 1, 2 });

		Assertions.assertThat(UsnRecordParser.parse(truncated)).extracting(UsnRecord::fileName)
				.containsExactly("a.jpg");
	}

	@Test
	void returnsEmptyForNullOrShortBuffers() {
		Assertions.assertThat(UsnRecordParser.parse(null)).isEmpty();
		Assertions.assertThat(UsnRecordParser.parse(new byte[10])).isEmpty();
	}
}