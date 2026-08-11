package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw;

import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class FileNotifyInformationParserTest {

	@Test
	void parsesASingleRelativePath() {
		Assertions.assertThat(FileNotifyInformationParser.parse(FileNotifyBuffers.buffer("2024\\05\\photo.jpg")))
				.containsExactly("2024\\05\\photo.jpg");
	}

	@Test
	void parsesEveryChainedEntryInOrder() {
		byte[] buffer = FileNotifyBuffers.buffer("a.jpg", "sub\\b.png", "sub\\deep\\c.mp4");

		Assertions.assertThat(FileNotifyInformationParser.parse(buffer)).containsExactly("a.jpg", "sub\\b.png",
				"sub\\deep\\c.mp4");
	}

	@Test
	void decodesUtf16Names() {
		Assertions.assertThat(FileNotifyInformationParser.parse(FileNotifyBuffers.buffer("férias\\praia.jpg")))
				.containsExactly("férias\\praia.jpg");
	}

	@Test
	void returnsEmptyForNullOrShortBuffers() {
		Assertions.assertThat(FileNotifyInformationParser.parse(null)).isEmpty();
		Assertions.assertThat(FileNotifyInformationParser.parse(new byte[4])).isEmpty();
	}

	/**
	 * Every {@code FILE_ACTION_*} yields a path, and that is the contract rather
	 * than an oversight: the answer to any of them is the same debounced pass, so
	 * a delete has to arrive for the reconcile to retire the row, and both sides
	 * of a rename have to arrive because either end may be the one inside the
	 * library. Until this test the fixtures wrote "added" on every entry, so a
	 * parser that started deciding on the field would have passed unnoticed.
	 */
	@Test
	void reportsAPathForEveryActionAndNotOnlyForAddedOnes() {
		byte[] buffer = FileNotifyBuffers.buffer(
				List.of(FileNotifyBuffers.ACTION_ADDED, FileNotifyBuffers.ACTION_REMOVED,
						FileNotifyBuffers.ACTION_MODIFIED, FileNotifyBuffers.ACTION_RENAMED_OLD_NAME,
						FileNotifyBuffers.ACTION_RENAMED_NEW_NAME),
				List.of("added.jpg", "removed.jpg", "modified.jpg", "was-called.jpg", "is-called.jpg"));

		Assertions.assertThat(FileNotifyInformationParser.parse(buffer)).containsExactly("added.jpg", "removed.jpg",
				"modified.jpg", "was-called.jpg", "is-called.jpg");
	}
}