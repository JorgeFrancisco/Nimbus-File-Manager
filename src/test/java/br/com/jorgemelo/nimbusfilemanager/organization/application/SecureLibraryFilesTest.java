package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWriteOff;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;

/**
 * The one place a file belonging to the user is changed, tested by what happens
 * to the file and to the watcher.
 *
 * <p>
 * The verified move has its own tests in {@link SecureFileMoveTest} and is not
 * repeated here - this port delegates to it, and asserting the same bytes twice
 * would only mean the delegation compiles. What is asserted here is everything
 * the port does on its own: the operations that used to be scattered across the
 * explorer, the quarantine and the conversion, each with its own copy of the
 * announcement.
 */
class SecureLibraryFilesTest {

	/**
	 * Spied rather than asked: the announcement is the subject here, and what
	 * proves it is that the door was told - with the role the change carries. The
	 * double still runs the effect, so the file system says the same thing it
	 * would in production, and nothing here reimplements the canonicalisation the
	 * database owns.
	 */
	private final SelfWrittenPathRegistry registry = spy(new SelfWriteOff());

	private final SecureLibraryFiles libraryFiles = new SecureLibraryFiles(
			new SecureFileMove(new OrganizationMoveVerifier(new FileHashService()), registry), registry);

	@Test
	void deletesTheFileAndTellsTheWatcherBeforeItGoes(@TempDir Path folder) throws IOException {
		Path photo = Files.writeString(folder.resolve("photo.jpg"), "bytes");

		libraryFiles.deleteFile(photo, 7L);

		assertThat(photo).doesNotExist();
		verify(registry).vacate(eq(7L), any(), eq(photo));
	}

	/**
	 * A deletion nobody queued is still this product's own work - the explorer
	 * deletes while somebody watches - so the announcement does not depend on
	 * there being an execution to attribute it to.
	 */
	@Test
	void announcesEvenWhenNoExecutionOwnsTheDeletion(@TempDir Path folder) throws IOException {
		Path photo = Files.writeString(folder.resolve("photo.jpg"), "bytes");

		libraryFiles.deleteFile(photo, null);

		verify(registry).vacate(isNull(), any(), eq(photo));
	}

	@Test
	void deletesAnEmptyDirectory(@TempDir Path folder) throws IOException {
		Path album = Files.createDirectory(folder.resolve("album"));

		libraryFiles.deleteEmptyDirectory(album, 7L);

		assertThat(album).doesNotExist();
		verify(registry).vacate(eq(7L), any(), eq(album));
	}

	/**
	 * The promise the method's name makes, kept by the file system rather than by
	 * a check that could go stale between looking and deleting: a directory with
	 * anything in it is refused, so this can never quietly become "delete this
	 * tree".
	 */
	@Test
	void refusesToDeleteADirectoryThatStillHasSomethingInIt(@TempDir Path folder) throws IOException {
		Path album = Files.createDirectory(folder.resolve("album"));

		Files.writeString(album.resolve("photo.jpg"), "bytes");

		assertThatThrownBy(() -> libraryFiles.deleteEmptyDirectory(album, 7L))
				.isInstanceOf(DirectoryNotEmptyException.class);

		assertThat(album).exists();
	}

	@Test
	void renamesADirectoryAndAnnouncesBothNames(@TempDir Path folder) throws IOException {
		Path album = Files.createDirectory(folder.resolve("album"));
		Path renamed = folder.resolve("viagem");

		libraryFiles.renameDirectory(album, renamed, 7L);

		assertThat(renamed).exists();
		assertThat(album).doesNotExist();
		// One announcement carrying both ends, which is what tells the two roles
		// apart: the folder being emptied and the one being filled.
		verify(registry).move(eq(7L), any(), eq(album), eq(renamed));
	}

	@Test
	void carriesTheModifiedTimeOntoTheFileAndAnnouncesIt(@TempDir Path folder) throws IOException {
		Path video = Files.writeString(folder.resolve("clip.mp4"), "bytes");

		FileTime original = FileTime.from(Instant.parse("2015-06-01T10:00:00Z"));

		libraryFiles.carryModifiedTime(video, original, 7L);

		assertThat(Files.getLastModifiedTime(video)).isEqualTo(original);
		verify(registry).occupy(eq(7L), any(), eq(video));
	}

	/**
	 * A conversion whose source was already gone has no time to carry, and that is
	 * not a failure worth stopping it for.
	 */
	@Test
	void doesNothingWhenThereIsNoTimeToCarry(@TempDir Path folder) throws IOException {
		Path video = Files.writeString(folder.resolve("clip.mp4"), "bytes");

		FileTime untouched = Files.getLastModifiedTime(video);

		libraryFiles.carryModifiedTime(video, null, 7L);

		assertThat(Files.getLastModifiedTime(video)).isEqualTo(untouched);
		verify(registry, never()).occupy(any(), any(), any());
	}

	/**
	 * A timestamp that cannot be written is logged and swallowed: the file is
	 * already in place and correct, and failing the conversion over its date would
	 * throw away the encode.
	 */
	@Test
	void survivesAFileThatCannotTakeTheTimestamp(@TempDir Path folder) {
		Path missing = folder.resolve("gone.mp4");

		libraryFiles.carryModifiedTime(missing, FileTime.from(Instant.EPOCH), 7L);

		assertThat(missing).doesNotExist();
	}

	@Test
	void reportsAMissingFileRatherThanPretendingItWasDeleted(@TempDir Path folder) {
		assertThatThrownBy(() -> libraryFiles.deleteFile(folder.resolve("gone.jpg"), null))
				.isInstanceOf(NoSuchFileException.class);
	}
}