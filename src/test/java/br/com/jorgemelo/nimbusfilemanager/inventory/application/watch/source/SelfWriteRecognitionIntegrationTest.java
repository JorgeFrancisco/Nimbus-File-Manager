package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.FileSystemChange;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.Changes;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationMoveVerifier;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureFileMove;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureLibraryFiles;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;
import br.com.jorgemelo.nimbusfilemanager.shared.application.library.LibraryFileMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * The watcher hearing this product's own work and saying nothing about it.
 *
 * <p>
 * Every capability that moves a file goes through the same door and names the
 * execution doing it; the door announces both ends before the bytes move and
 * settles the announcement after. The watcher, which is a different process
 * reading the same database, then has to recognise exactly those notifications
 * and no others - because a change nobody announced is a change the user made,
 * and answering it with a full inventory is the whole point of watching.
 *
 * <p>
 * Proved over the real registry and a real move: the recognition is a path
 * canonicalised under its flavor and matched by role in PostgreSQL, and a
 * double of it in test code would be a second authority on when two paths are
 * one place.
 */
class SelfWriteRecognitionIntegrationTest extends SharedPostgresIntegrationTest {

	@Autowired
	private SelfWrittenPathRegistry selfWrittenPathRegistry;

	@Autowired
	private ExecutionRepository executionRepository;

	private final ScanExclusionService scanExclusionService = mock(ScanExclusionService.class);

	/**
	 * A real run, because an announcement belongs to one: it is what holds the
	 * entry open while a long move is still going, and the database keeps the two
	 * together rather than taking the caller's word for it.
	 */
	private long working() {
		return executionRepository.saveAndFlush(Execution.builder().executionType(ExecutionType.EXPLORER_RENAME)
				.status(ExecutionStatus.RUNNING).startedAt(LocalDateTime.now()).executeFlag(true).build())
				.getId();
	}

	/**
	 * The door itself, wired as production wires it: what an Explorer rename, an
	 * organization move and an undo all call.
	 */
	private LibraryFileMutations libraryFiles() {
		return new SecureLibraryFiles(new SecureFileMove(new OrganizationMoveVerifier(new FileHashService()),
				selfWrittenPathRegistry), selfWrittenPathRegistry);
	}

	@Test
	void aMoveMadeThroughTheLibraryDoorIsAccountedForAtBothEnds(@TempDir Path folder) throws IOException {
		Path source = Files.writeString(folder.resolve("before.mp4"), "content");
		Path target = folder.resolve("after.mp4");
		Path theirs = folder.resolve("edited-by-somebody.mp4");

		libraryFiles().move(source, target, false, working());

		List<FileSystemChange> reported = List.of(Changes.renamed(source, target), Changes.modified(theirs));

		assertThat(filtering(folder, reported).pollChanges()).as("ours is explained; the other file is not")
				.containsExactly(Changes.modified(theirs));
	}

	/**
	 * A path this product just emptied is one somebody is likely to fill, and the
	 * file that lands there is theirs. Emptying explains the path going quiet and
	 * nothing about what arrives next.
	 */
	@Test
	void aFileArrivingAtAPathThisProductJustEmptiedIsNotOurs(@TempDir Path folder) throws IOException {
		Path vacated = Files.writeString(folder.resolve("moved-away.mp4"), "content");

		libraryFiles().move(vacated, folder.resolve("its-new-home.mp4"), false, working());

		Files.writeString(vacated, "a different file the user just saved");

		assertThat(filtering(folder, List.of(Changes.created(vacated))).pollChanges())
				.as("the arrival is not the departure, however alike the two paths look")
				.containsExactly(Changes.created(vacated));
	}

	/**
	 * @param root the watched folder, which the filter needs because it judges
	 * hidden-ness only below it - the temporary folder these tests run in lives
	 * under one Windows marks hidden, and judging absolute ancestors would drop
	 * every change before any of this was decided
	 */
	private SelfWriteAwareFileChangeSource filtering(Path root, List<FileSystemChange> reported) {
		FileChangeSource delegate = mock(FileChangeSource.class);

		when(delegate.pollChanges()).thenReturn(reported);
		when(delegate.root()).thenReturn(root);

		return new SelfWriteAwareFileChangeSource(delegate, selfWrittenPathRegistry, scanExclusionService);
	}
}