package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateDeletionPersistence;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.MoveIntegrityException;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationMoveVerifier;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureFileMove;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureLibraryFiles;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MoveBaseline;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.IntakeOutcome;
import br.com.jorgemelo.nimbusfilemanager.settings.application.QuarantineFolderPolicy;
import br.com.jorgemelo.nimbusfilemanager.shared.application.InMemorySelfWrittenPaths;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;
import br.com.jorgemelo.nimbusfilemanager.shared.application.library.LibraryFileMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

class QuarantineIntakeServiceTest {

	private final SelfWrittenPathRegistry pathRegistry = new SelfWrittenPathRegistry(new InMemorySelfWrittenPaths(),
			Clock.systemDefaultZone());
	private final DuplicateDeletionPersistence persistence = mock(DuplicateDeletionPersistence.class);
	private final QuarantineFolderPolicy quarantineFolderPolicy = mock(QuarantineFolderPolicy.class);
	private final QuarantineIntakeService service = new QuarantineIntakeService(persistence,
			new SecureLibraryFiles(
					new SecureFileMove(new OrganizationMoveVerifier(new FileHashService()), pathRegistry),
					pathRegistry), quarantineFolderPolicy);

	private final Execution execution = Execution.builder().id(1L).build();

	/**
	 * Where the quarantine is comes from the folder policy now - a question about
	 * configuration, answered by the class that already validates that folder. This
	 * kept its own test because callers still ask the intake, and what they get has
	 * to be what the policy says.
	 */
	@Test
	void reportsTheConfiguredQuarantineRoot(@TempDir Path trash) {
		when(quarantineFolderPolicy.root()).thenReturn(Optional.of(trash));

		Assertions.assertThat(service.root()).contains(trash);
	}

	@Test
	void reportsNoRootWhileTheQuarantineFolderIsUnconfigured() {
		when(quarantineFolderPolicy.root()).thenReturn(Optional.empty());

		Assertions.assertThat(service.root()).isEmpty();

	}

	@Test
	void movesTheFileUnderTheExecutionFolderAndRecordsTheCallersReason(@TempDir Path tmp) throws Exception {
		Path library = Files.createDirectories(tmp.resolve("library"));
		Path trash = tmp.resolve("trash");
		Path original = Files.writeString(library.resolve("clip.mp4"), "content");

		CatalogFile file = file(original);

		IntakeOutcome outcome = service.intake(execution, file, trash, MovementReason.CONVERTED_QUARANTINED, null);

		Path quarantined = trash.resolve("exec-1").resolve("10__clip.mp4");

		Assertions.assertThat(outcome).isEqualTo(IntakeOutcome.MOVED);
		Assertions.assertThat(original).doesNotExist();
		Assertions.assertThat(quarantined).hasContent("content");

		verify(persistence).persistQuarantine(execution, file, original, quarantined,
				MovementReason.CONVERTED_QUARANTINED);
	}

	@Test
	void skipsAnEntryThatIsNoLongerActive(@TempDir Path tmp) throws Exception {
		Path original = Files.writeString(tmp.resolve("clip.mp4"), "content");

		CatalogFile file = file(original);

		file.setLifecycleStatus(LifecycleStatus.DELETED);

		Assertions
				.assertThat(service.intake(execution, file, tmp.resolve("trash"), MovementReason.CONVERTED_QUARANTINED,
						null))
				.isEqualTo(IntakeOutcome.SKIPPED);
		Assertions.assertThat(original).exists();
	}

	@Test
	void skipsAFileThatIsAlreadyInsideTheQuarantine(@TempDir Path tmp) throws Exception {
		Path trash = Files.createDirectories(tmp.resolve("trash"));
		Path inside = Files.writeString(trash.resolve("clip.mp4"), "content");

		Assertions.assertThat(service.intake(execution, file(inside), trash, MovementReason.DUPLICATE_QUARANTINED,
				null))
				.isEqualTo(IntakeOutcome.SKIPPED);

		verify(persistence, never()).persistQuarantine(any(), any(), any(), any(), any());
	}

	@Test
	void skipsAFileThatIsNoLongerOnDisk(@TempDir Path tmp) {
		Assertions.assertThat(service.intake(execution, file(tmp.resolve("gone.mp4")), tmp.resolve("trash"),
				MovementReason.DUPLICATE_QUARANTINED, null)).isEqualTo(IntakeOutcome.SKIPPED);
	}

	@Test
	void putsTheFileBackWhenTheCatalogWriteFails(@TempDir Path tmp) throws Exception {
		Path library = Files.createDirectories(tmp.resolve("library"));
		Path trash = tmp.resolve("trash");
		Path original = Files.writeString(library.resolve("clip.mp4"), "content");

		doThrow(new IllegalStateException("db down")).when(persistence).persistQuarantine(any(), any(), any(), any(),
				any());

		Assertions.assertThat(service.intake(execution, file(original), trash, MovementReason.CONVERTED_QUARANTINED,
				null))
				.isEqualTo(IntakeOutcome.ERROR);
		Assertions.assertThat(original).hasContent("content");
	}

	@Test
	void leavesTheFileInQuarantineWhenBothTheCatalogWriteAndTheRollbackFail(@TempDir Path tmp) throws Exception {
		Path library = Files.createDirectories(tmp.resolve("library"));
		Path trash = tmp.resolve("trash");
		Path original = Files.writeString(library.resolve("clip.mp4"), "content");

		// The catalog write fails and re-creates the original path, so the physical
		// roll-back (which never overwrites) cannot put the file back.
		doAnswer(_ -> {
			Files.writeString(original, "blocker");

			throw new IllegalStateException("db down");
		}).when(persistence).persistQuarantine(any(), any(), any(), any(), any());

		Assertions.assertThat(service.intake(execution, file(original), trash, MovementReason.CONVERTED_QUARANTINED,
				null))
				.isEqualTo(IntakeOutcome.ERROR);
		Assertions.assertThat(trash.resolve("exec-1").resolve("10__clip.mp4")).exists();
	}

	@Test
	void skipsAShortcutInsteadOfMovingWhatItPointsAt(@TempDir Path tmp) throws Exception {
		Path shortcut = Files.writeString(tmp.resolve("clip.lnk"), "shortcut");

		Assertions.assertThat(
				service.intake(execution, file(shortcut), tmp.resolve("trash"), MovementReason.CONVERTED_QUARANTINED,
						null))
				.isEqualTo(IntakeOutcome.SKIPPED);
		Assertions.assertThat(shortcut).exists();

		verify(persistence, never()).persistQuarantine(any(), any(), any(), any(), any());
	}

	@Test
	void putsTheFileBackWhenTheMoveFailsItsIntegrityCheck(@TempDir Path tmp) throws Exception {
		Path library = Files.createDirectories(tmp.resolve("library"));
		Path trash = tmp.resolve("trash");
		Path original = Files.writeString(library.resolve("clip.mp4"), "content");

		// A move that physically relocates the file and then fails the SHA-256 verify -
		// an on-disk corruption caught mid-move. Nothing may be left half-quarantined.
		OrganizationMoveVerifier verifier = mock(OrganizationMoveVerifier.class);

		when(verifier.capture(any())).thenReturn(new MoveBaseline(7L, "sha"));
		doThrow(new MoveIntegrityException("sha mismatch")).when(verifier).verify(any(), any(), any());

		QuarantineIntakeService failing = new QuarantineIntakeService(persistence,
				new SecureLibraryFiles(new SecureFileMove(verifier, pathRegistry), pathRegistry),
						quarantineFolderPolicy);

		Assertions.assertThat(failing.intake(execution, file(original), trash, MovementReason.DUPLICATE_QUARANTINED,
				null))
				.isEqualTo(IntakeOutcome.ERROR);
		Assertions.assertThat(original).hasContent("content");
		Assertions.assertThat(trash.resolve("exec-1").resolve("10__clip.mp4")).doesNotExist();

		verify(persistence, never()).persistQuarantine(any(), any(), any(), any(), any());
	}

	/**
	 * The worst case of the three: the file physically left the library, the verify
	 * refused it and putting it back failed too. Nothing can be done about the file
	 * from here, so what matters is that the catalog is not told the move happened
	 * and that the failure is reported as one.
	 */
	@Test
	void reportsAnErrorWhenTheFileIsLeftInQuarantineByAFailedRollback(@TempDir Path tmp) throws Exception {
		Path library = Files.createDirectories(tmp.resolve("library"));
		Path trash = tmp.resolve("trash");
		Path original = Files.writeString(library.resolve("clip.mp4"), "content");

		LibraryFileMutations failing = mock(LibraryFileMutations.class);

		doAnswer(_ -> {
			// The move happened and then failed its verify, which is what leaves the file
			// on the far side with nothing pointing at it.
			Path target = Files.createDirectories(trash.resolve("exec-1")).resolve("10__clip.mp4");

			Files.move(original, target);

			throw new MoveIntegrityException("sha mismatch");
		}).when(failing).move(any(), any(), anyBoolean(), any());

		when(failing.rollback(any(), any())).thenReturn(false);

		QuarantineIntakeService orphaning = new QuarantineIntakeService(persistence, failing, quarantineFolderPolicy);

		Assertions.assertThat(orphaning.intake(execution, file(original), trash,
				MovementReason.DUPLICATE_QUARANTINED, 1L)).isEqualTo(IntakeOutcome.ERROR);
		Assertions.assertThat(original).doesNotExist();

		verify(failing).rollback(any(), any());
		verify(persistence, never()).persistQuarantine(any(), any(), any(), any(), any());
	}

	private CatalogFile file(Path path) {
		return CatalogFile.builder().id(10L).fileKey(path.toString()).fileName(path.getFileName().toString())
				.lifecycleStatus(LifecycleStatus.ACTIVE).build();
	}
}