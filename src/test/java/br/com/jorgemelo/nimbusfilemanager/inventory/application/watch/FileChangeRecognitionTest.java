package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.CatalogTimestamp;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.ContentChangeDetector;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.ContentVerificationLauncher;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.application.LocationChangeException;
import br.com.jorgemelo.nimbusfilemanager.shared.application.catalog.CatalogConvergenceMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.LocationChange;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FilesystemIdentityKind;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationChangeFailure;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.FilesystemIdentity;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.KnownContentRow;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.CatalogLocationWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * What the watcher can conclude on its own, and what it has to hand over.
 *
 * <p>
 * Answering "true" here means the catalog is up to date and the change needs
 * nothing further; "false" means the ordinary debounced pass has to look, which
 * is slower and sees everything. Neither is a failure - what matters is that
 * nothing is concluded from evidence that does not support it, because a wrong
 * conclusion here writes a fact about a file nobody touched.
 *
 * <p>
 * The detector is the real one: what a stat means is its rule and is proved on
 * its own. What is under test is the recognition - which questions get asked,
 * of what, and what is done with the answers.
 */
class FileChangeRecognitionTest {

	private static final Instant NOW = Instant.parse("2026-08-14T06:00:00Z");
	private static final Instant JOURNALLED_AT = Instant.parse("2026-08-14T05:00:00Z");
	private static final String KNOWN = "a".repeat(64);

	private final CatalogFileLocationRepository catalogFileLocationRepository = mock(
			CatalogFileLocationRepository.class);
	private final CatalogLocationWriter catalogLocationWriter = mock(CatalogLocationWriter.class);
	private final CatalogConvergenceMutations catalogMutations = mock(CatalogConvergenceMutations.class);
	private final ContentVerificationLauncher contentVerificationLauncher = mock(ContentVerificationLauncher.class);

	private final FileChangeRecognition recognition = new FileChangeRecognition(catalogFileLocationRepository,
			catalogLocationWriter, catalogMutations, new ContentChangeDetector(), contentVerificationLauncher,
			Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void aFileLeavingIsNotSomethingThisCanConclude(@TempDir Path folder) {
		Assertions.assertThat(recognition.recognise(Changes.deleted(folder.resolve("gone.jpg"))))
				.as("whether it is missing or was moved out is what a pass over the tree decides").isFalse();
	}

	@Test
	void aFileArrivingWithNothingToTieItToAnythingIsNotRecognised(@TempDir Path folder) {
		Assertions.assertThat(recognition.recognise(Changes.created(folder.resolve("new.jpg")))).isFalse();

		verify(catalogLocationWriter, never()).relocate(any());
	}

	// ----------------------------------------------------------------
	// A file the catalog knows, written to
	// ----------------------------------------------------------------

	@Test
	void aWriteThatLeftTheFileExactlyAsItWasStopsHere(@TempDir Path folder) throws IOException {
		Path file = Files.writeString(folder.resolve("photo.jpg"), "content");

		knownAt(file, KNOWN, Files.size(file), Files.getLastModifiedTime(file).toInstant(), null);

		Assertions.assertThat(recognition.recognise(Changes.modified(file)))
				.as("an attribute change is a notification too, and walking the library for it is waste").isTrue();

		verify(contentVerificationLauncher, never()).verify(any(), anyString(), any(), any());
	}

	@Test
	void aWriteThatChangedTheDescriptionAsksForTheFileToBeRead(@TempDir Path folder) throws IOException {
		Path file = Files.writeString(folder.resolve("photo.jpg"), "content");

		knownAt(file, KNOWN, 999_999L, Files.getLastModifiedTime(file).toInstant(), null);

		Assertions.assertThat(recognition.recognise(Changes.modified(file))).isTrue();

		// Read by a durable execution and never on the thread that polls: a gigabyte
		// hashed here is a gigabyte between the operating system and the next look.
		verify(contentVerificationLauncher).verify(7L, PathUtils.normalize(file), NOW,
				ExecutionTrigger.FILE_EVENT);
	}

	@Test
	void aWriteToAFileTheCatalogDoesNotHoldIsTheWalksJob(@TempDir Path folder) throws IOException {
		Path file = Files.writeString(folder.resolve("stranger.jpg"), "content");

		when(catalogFileLocationRepository.findKnownContentByPath(anyString(), anyString()))
				.thenReturn(Optional.empty());

		Assertions.assertThat(recognition.recognise(Changes.modified(file))).isFalse();
	}

	@Test
	void aWriteToSomethingThatIsNoLongerThereConcludesNothing(@TempDir Path folder) {
		Path gone = folder.resolve("vanished.jpg");

		knownAt(gone, KNOWN, 10L, NOW, null);

		Assertions.assertThat(recognition.recognise(Changes.modified(gone)))
				.as("it cannot be stat'ed, so there is nothing to compare and nothing to say").isFalse();
	}

	/**
	 * The bytes are the ones on record and a different object is holding them,
	 * which a stat cannot settle: the digest is what says whether an edit landed.
	 */
	@Test
	void aWriteByADifferentObjectIsVerifiedEvenWhenNothingElseMoved(@TempDir Path folder) throws IOException {
		Path file = Files.writeString(folder.resolve("photo.jpg"), "content");

		knownAt(file, KNOWN, Files.size(file), Files.getLastModifiedTime(file).toInstant(), identity("11"));

		Assertions.assertThat(recognition.recognise(Changes.withIdentity(Changes.modified(file), identity("22"))))
				.isTrue();

		verify(contentVerificationLauncher).verify(eq(7L), anyString(), any(), any());
	}

	// ----------------------------------------------------------------
	// A rename
	// ----------------------------------------------------------------

	/**
	 * Applications save by writing a temporary file and swapping it in, so a
	 * rename onto a catalogued file is the ordinary shape of an edit. The
	 * catalogued file keeps its identity - what the user did was edit that
	 * picture - and what its bytes are now is the digest's to say.
	 */
	@Test
	void aRenameOntoACataloguedFileIsThatFileBeingEdited(@TempDir Path folder) {
		Path temporary = folder.resolve("photo.jpg.tmp");
		Path photo = folder.resolve("photo.jpg");

		knownAt(photo, KNOWN, 10L, NOW, null);

		Assertions.assertThat(recognition.recognise(Changes.renamed(temporary, photo))).isTrue();

		verify(contentVerificationLauncher).verify(eq(7L), eq(PathUtils.normalize(photo)), eq(NOW), any());
		verify(catalogLocationWriter, never()).relocate(any());
	}

	@Test
	void aRenameOfACataloguedFileFollowsItToItsNewName(@TempDir Path folder) {
		Path before = folder.resolve("before.jpg");
		Path after = folder.resolve("after.jpg");

		when(catalogFileLocationRepository.findKnownContentByPath(anyString(), anyString()))
				.thenReturn(Optional.empty());
		when(catalogFileLocationRepository.findPresentByPath(eq(PathUtils.normalize(before)), anyString()))
				.thenReturn(Optional.of(CatalogFiles.at(7L, before)));

		Assertions.assertThat(recognition.recognise(Changes.renamed(before, after))).isTrue();

		ArgumentCaptor<LocationChange> change = ArgumentCaptor.forClass(LocationChange.class);

		verify(catalogLocationWriter).relocate(change.capture());

		Assertions.assertThat(change.getValue().catalogFileId()).isEqualTo(7L);
		Assertions.assertThat(change.getValue().newPath()).isEqualTo(after);
		Assertions.assertThat(change.getValue().provenance().source()).isEqualTo(CatalogEventSources.WATCHER);
		Assertions.assertThat(change.getValue().provenance().evidence())
				.as("the pair of names is what was observed").isEqualTo(CatalogEventEvidence.OS_RENAME_PAIR);
		Assertions.assertThat(change.getValue().provenance().occurredAt()).isEqualTo(NOW);
	}

	/**
	 * A rename whose old name the catalog has no present file at. It may be a file
	 * nobody catalogued, or one the catalog only remembers there - and a file that
	 * merely used to be somewhere is not what the operating system just renamed.
	 */
	@Test
	void aRenameOfSomethingTheCatalogHasNoPresentFileForIsNotRecognised(@TempDir Path folder) {
		Path before = folder.resolve("before.jpg");
		Path after = folder.resolve("after.jpg");

		when(catalogFileLocationRepository.findKnownContentByPath(anyString(), anyString()))
				.thenReturn(Optional.empty());
		when(catalogFileLocationRepository.findPresentByPath(anyString(), anyString())).thenReturn(Optional.empty());

		Assertions.assertThat(recognition.recognise(Changes.renamed(before, after))).isFalse();

		verify(catalogLocationWriter, never()).relocate(any());
	}

	/**
	 * The identity is stronger evidence than the pair of names, and it is what a
	 * file moved by something that reported no old name is found by.
	 */
	@Test
	void aFileFoundByItsOwnIdentityIsFollowedOnThatEvidence(@TempDir Path folder) {
		Path elsewhere = folder.resolve("elsewhere.jpg");

		carrying(identity("11"), located(7L, folder.resolve("was-here.jpg")));

		Assertions.assertThat(recognition.recognise(Changes.withIdentity(Changes.created(elsewhere), identity("11"))))
				.isTrue();

		ArgumentCaptor<LocationChange> change = ArgumentCaptor.forClass(LocationChange.class);

		verify(catalogLocationWriter).relocate(change.capture());

		Assertions.assertThat(change.getValue().provenance().evidence())
				.isEqualTo(CatalogEventEvidence.FILESYSTEM_IDENTITY_MATCH);
	}

	@Test
	void anIdentityThatNamesMoreThanOnePlaceConcludesNothing(@TempDir Path folder) {
		Path arrived = folder.resolve("arrived.jpg");

		when(catalogFileLocationRepository.findByFilesystemIdentity(any(), anyString(), anyString()))
				.thenReturn(List.of(located(7L, folder.resolve("one.jpg")), located(8L, folder.resolve("two.jpg"))));

		Assertions.assertThat(recognition.recognise(Changes.withIdentity(Changes.created(arrived), identity("11"))))
				.as("any choice would be a guess, and a guess writes a fact about the wrong file").isFalse();

		verify(catalogLocationWriter, never()).relocate(any());
	}

	/**
	 * The journal replay and the live watch overlap at startup on purpose, so one
	 * change arrives twice - and the second time the world is already what it
	 * describes.
	 */
	@Test
	void aChangeThatDescribesTheWorldAsItAlreadyIsNeedsNothing(@TempDir Path folder) {
		Path file = folder.resolve("photo.jpg");

		carrying(identity("11"), located(7L, file));

		Assertions.assertThat(recognition.recognise(Changes.withIdentity(Changes.created(file), identity("11"))))
				.isTrue();

		verify(catalogLocationWriter, never()).relocate(any());
	}

	@Test
	void aRelocationTheDoorRefusesGoesBackToTheOrdinaryPass(@TempDir Path folder) {
		Path before = folder.resolve("before.jpg");
		Path after = folder.resolve("after.jpg");

		when(catalogFileLocationRepository.findKnownContentByPath(anyString(), anyString()))
				.thenReturn(Optional.empty());
		when(catalogFileLocationRepository.findPresentByPath(eq(PathUtils.normalize(before)), anyString()))
				.thenReturn(Optional.of(CatalogFiles.at(7L, before)));
		doThrow(new LocationChangeException(LocationChangeFailure.PATH_OCCUPIED, "taken", null))
				.when(catalogLocationWriter).relocate(any());

		Assertions.assertThat(recognition.recognise(Changes.renamed(before, after)))
				.as("the world moved under the observation; the pass that sees everything settles it").isFalse();
	}

	/**
	 * The journal knows when each change was recorded, because it replays a window
	 * the application was absent for. The live watch does not, and for it the
	 * honest answer is the moment the change was accepted.
	 */
	@Test
	void afactFromTheJournalKeepsTheMomentTheJournalRecorded(@TempDir Path folder) {
		Path before = folder.resolve("before.jpg");
		Path after = folder.resolve("after.jpg");

		when(catalogFileLocationRepository.findKnownContentByPath(anyString(), anyString()))
				.thenReturn(Optional.empty());
		when(catalogFileLocationRepository.findPresentByPath(eq(PathUtils.normalize(before)), anyString()))
				.thenReturn(Optional.of(CatalogFiles.at(7L, before)));

		recognition.recognise(Changes.fromJournal(Changes.renamed(before, after), JOURNALLED_AT));

		ArgumentCaptor<LocationChange> change = ArgumentCaptor.forClass(LocationChange.class);

		verify(catalogLocationWriter).relocate(change.capture());

		Assertions.assertThat(change.getValue().provenance().occurredAt()).isEqualTo(JOURNALLED_AT);
	}

	// ----------------------------------------------------------------
	// A folder
	// ----------------------------------------------------------------

	@Test
	void aFolderThatMovedTakesEverythingCataloguedUnderItAlong(@TempDir Path library) {
		Path before = library.resolve("2023");
		Path after = library.resolve("2024");

		when(catalogMutations.repointFolder(anyString(), anyString(), any(), anyString(), anyString())).thenReturn(9);

		Assertions.assertThat(recognition.recognise(Changes.renamedDirectory(before, after))).isTrue();

		// One notification arrives for a folder and none for its descendants, so the
		// bulk door is the only thing that can answer for ten thousand files.
		verify(catalogMutations).repointFolder(PathUtils.normalize(before), PathUtils.normalize(after), NOW,
				CatalogEventSources.WATCHER, CatalogEventEvidence.ANCESTOR_RELOCATED);
	}

	@Test
	void aFolderReportedAsMovingInsideItselfDescribesSomethingElse(@TempDir Path library) {
		Path outer = library.resolve("photos");

		Assertions.assertThat(recognition.recognise(Changes.renamedDirectory(outer, outer.resolve("2024")))).isFalse();

		verify(catalogMutations, never()).repointFolder(anyString(), anyString(), any(), anyString(), anyString());
	}

	@Test
	void aFolderChangeThatIsNotARenameSaysNothingAboutWhereAnythingIs(@TempDir Path library) {
		Assertions.assertThat(recognition.recognise(Changes.createdDirectory(library.resolve("new-folder")))).isFalse();

		verify(catalogMutations, never()).repointFolder(anyString(), anyString(), any(), anyString(), anyString());
	}

	@Test
	void aFolderRelocationTheDoorRefusesGoesBackToTheOrdinaryPass(@TempDir Path library) {
		when(catalogMutations.repointFolder(anyString(), anyString(), any(), anyString(), anyString()))
				.thenThrow(new IllegalStateException("the destination filled up"));

		Assertions.assertThat(recognition
				.recognise(Changes.renamedDirectory(library.resolve("2023"), library.resolve("2024"))))
				.as("nothing is undone: the file system, not this, is the authority on what happened").isFalse();
	}

	private void knownAt(Path path, String sha256, Long sizeBytes, Instant modifiedAt, FilesystemIdentity identity) {
		// Built first: this stubs a mock of its own, and doing that inside when(...)
		// leaves the outer stubbing unfinished. The instant goes in at the precision
		// the catalog stores, because this stands for a row that was read back from it
		// - a fixture keeping nanoseconds would describe a state the column cannot
		// hold, and every comparison against the disk would report a change.
		Optional<KnownContentRow> known = Optional
				.of(row(sha256, sizeBytes, CatalogTimestamp.observed(modifiedAt), identity));

		when(catalogFileLocationRepository.findKnownContentByPath(eq(PathUtils.normalize(path)), anyString()))
				.thenReturn(known);
	}

	private void carrying(FilesystemIdentity identity, CatalogFileLocation location) {
		when(catalogFileLocationRepository.findByFilesystemIdentity(identity.kind(), identity.scope(),
				identity.value())).thenReturn(List.of(location));
	}

	private CatalogFileLocation located(long catalogFileId, Path path) {
		CatalogFile file = CatalogFiles.at(catalogFileId, path);

		return file.getLocation();
	}

	private KnownContentRow row(String sha256, Long sizeBytes, Instant modifiedAt, FilesystemIdentity identity) {
		KnownContentRow row = mock(KnownContentRow.class);

		lenient().when(row.getCatalogFileId()).thenReturn(7L);
		lenient().when(row.getSha256()).thenReturn(sha256);
		lenient().when(row.getSizeBytes()).thenReturn(sizeBytes);
		lenient().when(row.getModifiedAt()).thenReturn(modifiedAt);
		lenient().when(row.getFilesystemIdentityKind())
				.thenReturn(identity == null ? null : identity.kind().name());
		lenient().when(row.getFilesystemIdentityScope()).thenReturn(identity == null ? null : identity.scope());
		lenient().when(row.getFilesystemIdentityValue()).thenReturn(identity == null ? null : identity.value());

		return row;
	}

	private FilesystemIdentity identity(String value) {
		return new FilesystemIdentity(FilesystemIdentityKind.WINDOWS_FILE_ID, "volume-under-test", value);
	}
}