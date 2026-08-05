package br.com.jorgemelo.nimbusfilemanager.backup.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Putting the finished backup where the user keeps their backups.
 *
 * <p>
 * The destination is a folder the application declares its own - excluded from
 * the scan and dropped by the watcher before anything else is asked - so this
 * is not a change to the user's library and does not go through the library
 * port. What the port did contribute here, and is kept, is the verification:
 * when the destination is another disk, which is the whole point of taking a
 * backup, the move is a copy and nothing else in the system would notice one
 * that arrived wrong.
 */
class BackupDeliveryTest {

	private final BackupDelivery delivery = new BackupDelivery(new BackupDigest());

	@Test
	void deliversTheArtefactAndLeavesNothingBehind(@TempDir Path folder) throws IOException {
		Path artifact = artifact(folder, "the backup");

		Path target = folder.resolve("backups").resolve("2026").resolve("backup.zip");

		delivery.deliver(artifact, target);

		Assertions.assertThat(Files.readString(target)).isEqualTo("the backup");
		Assertions.assertThat(Files.exists(artifact)).isFalse();
	}

	/** The folder the user chose may not exist yet; the first backup creates it. */
	@Test
	void createsTheDestinationFolderWhenItIsNotThereYet(@TempDir Path folder) throws IOException {
		Path artifact = artifact(folder, "content");

		Path target = folder.resolve("deep").resolve("deeper").resolve("backup.zip");

		delivery.deliver(artifact, target);

		Assertions.assertThat(Files.isDirectory(target.getParent())).isTrue();
		Assertions.assertThat(Files.exists(target)).isTrue();
	}

	/**
	 * A backup of the same name is the previous one, and the new artefact was
	 * already validated before it got here: it replaces it rather than failing and
	 * leaving the user without today's copy.
	 */
	@Test
	void replacesABackupAlreadyAtTheDestination(@TempDir Path folder) throws IOException {
		Path artifact = artifact(folder, "today");

		Path target = Files.writeString(folder.resolve("backup.zip"), "yesterday");

		delivery.deliver(artifact, target);

		Assertions.assertThat(Files.readString(target)).isEqualTo("today");
	}

	/**
	 * The reason this verification exists: when the destination is another disk the
	 * move is a copy, and a copy that arrived wrong is a rescue that will not
	 * rescue anything. What is kept is nothing - a file that only looks like a
	 * backup is worse than an error, because it is found on the day it is needed.
	 */
	@Test
	void discardsTheBackupWhenWhatArrivedIsNotWhatLeft(@TempDir Path folder) throws IOException {
		BackupDigest disagreeing = mock(BackupDigest.class);

		Path artifact = artifact(folder, "the backup");

		Path target = folder.resolve("backups").resolve("backup.zip");

		when(disagreeing.of(artifact)).thenReturn("aaaa");
		when(disagreeing.of(target)).thenReturn("bbbb");

		Assertions.assertThatThrownBy(() -> new BackupDelivery(disagreeing).deliver(artifact, target))
				.isInstanceOf(IOException.class).hasMessageContaining("did not arrive intact");

		Assertions.assertThat(Files.exists(target)).isFalse();
	}

	/** Nothing to deliver is an IO failure, not a silent success. */
	@Test
	void failsWhenThereIsNoArtefactToDeliver(@TempDir Path folder) {
		Path missing = folder.resolve("never-built.zip");

		Path target = folder.resolve("backups").resolve("backup.zip");

		Assertions.assertThatThrownBy(() -> delivery.deliver(missing, target)).isInstanceOf(IOException.class);

		Assertions.assertThat(Files.exists(target)).isFalse();
	}

	private Path artifact(Path folder, String content) throws IOException {
		Path staging = Files.createDirectories(folder.resolve("staging"));

		return Files.writeString(staging.resolve("backup.zip"), content);
	}
}