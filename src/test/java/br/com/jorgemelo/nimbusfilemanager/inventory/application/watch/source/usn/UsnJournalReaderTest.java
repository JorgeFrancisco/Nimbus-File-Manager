package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.constants.UsnReason;

class UsnJournalReaderTest {

	private static final Path ROOT = Path.of("/library").toAbsolutePath();
	private static final long SUB_A = 10L;
	private static final int BUFFER = 65_536;

	private final UsnPathResolver resolver = frn -> Optional
			.ofNullable(Map.of(SUB_A, ROOT.resolve("a")).get(frn));

	private UsnJournalReader reader(UsnVolume volume, long startUsn) {
		return new UsnJournalReader(volume, new UsnChangeInterpreter(ROOT, resolver), BUFFER, startUsn);
	}

	@Test
	void drainsEveryBatchAndAdvancesTheCursorToTheEnd() {
		byte[] first = UsnRecordBuffers.recordBytes(1L, 100L, SUB_A, UsnReason.FILE_CREATE,
				UsnRecordBuffers.ATTR_NORMAL,
				"one.jpg");
		byte[] second = UsnRecordBuffers.recordBytes(2L, 101L, SUB_A, UsnReason.FILE_CREATE,
				UsnRecordBuffers.ATTR_NORMAL,
				"two.jpg");

		CountingUsnVolume volume = new CountingUsnVolume(List.of(new UsnReadResult(50L, first), new UsnReadResult(100L,
				second)));

		UsnJournalReader reader = reader(volume, 0L);

		List<Path> changed = reader.poll();

		Assertions.assertThat(changed).containsExactly(ROOT.resolve("a").resolve("one.jpg"),
				ROOT.resolve("a").resolve("two.jpg"));
		Assertions.assertThat(reader.nextUsn()).isEqualTo(100L);
		Assertions.assertThat(reader.consumeOverflow()).isFalse();
	}

	@Test
	void aggregatesTheReconcileSignalFromADirectoryMove() {
		byte[] batch = UsnRecordBuffers.concat(
				UsnRecordBuffers.recordBytes(1L, 500L, SUB_A, UsnReason.RENAME_OLD_NAME,
						UsnRecordBuffers.ATTR_DIRECTORY,
						"2023"),
				UsnRecordBuffers.recordBytes(2L, 500L, SUB_A, UsnReason.RENAME_NEW_NAME,
						UsnRecordBuffers.ATTR_DIRECTORY,
						"2024"));

		CountingUsnVolume volume = new CountingUsnVolume(List.of(new UsnReadResult(60L, batch)));

		UsnJournalReader reader = reader(volume, 0L);

		reader.poll();

		Assertions.assertThat(reader.consumeOverflow()).isTrue();
		Assertions.assertThat(reader.consumeOverflow()).isFalse();
	}

	@Test
	void requestReconcileRaisesTheOverflowFlag() {
		UsnJournalReader reader = reader(new CountingUsnVolume(List.of()), 10L);

		reader.requestReconcile();

		Assertions.assertThat(reader.consumeOverflow()).isTrue();
	}

	@Test
	void stopsWhenTheJournalDoesNotAdvanceToAvoidSpinning() {
		byte[] batch = UsnRecordBuffers.recordBytes(1L, 100L, SUB_A, UsnReason.FILE_CREATE,
				UsnRecordBuffers.ATTR_NORMAL,
				"stuck.jpg");

		// nextStartUsn (100) does not move past the start cursor (100): one pass, then
		// stop.
		CountingUsnVolume volume = new CountingUsnVolume(List.of(new UsnReadResult(100L, batch), new UsnReadResult(100L,
				batch)));

		UsnJournalReader reader = reader(volume, 100L);

		reader.poll();

		Assertions.assertThat(volume.reads()).isEqualTo(1);
		Assertions.assertThat(reader.nextUsn()).isEqualTo(100L);
	}
}