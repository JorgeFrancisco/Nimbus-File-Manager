package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.application.catalog.CatalogConvergenceMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.CatalogFactProvenance;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PreparedMovement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * A rename asked for from the Files screen whose worker died after the disk and
 * before the catalog.
 *
 * <p>
 * The retry cannot work out what it was doing by looking around: the source is
 * gone, so the catalog no longer names anything there, and the destination
 * being occupied reads as somebody else's file in the way. Both readings used
 * to end the run - one as "nothing to do", the other as a name conflict - and
 * left the catalog pointing at a path the file had already left.
 *
 * <p>
 * The operations reserved before the move are what the retry reads instead.
 */
@SpringBootTest
@Testcontainers
class ExplorerCrashRecoveryIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

	@Autowired
	private ExplorerRenameService explorerRenameService;

	@Autowired
	private ExplorerRelocationPlan explorerRelocationPlan;

	@Autowired
	private CatalogConvergenceMutations catalogMutations;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private AppSettingService appSettingService;

	@Test
	void aFileThatAlreadyMovedIsRepointedWithoutBeingMovedAgain(@TempDir Path library) throws IOException {
		Path before = library.resolve("before.jpg");
		Path after = library.resolve("after.jpg");

		appSettingService.update(SettingsConstants.WATCH_FOLDER, library.toString(), "system");

		CatalogFile file = CatalogFiles.catalogued(new TransactionTemplate(transactionManager),
				catalogFileRepository, catalogFileLocationRepository, before);

		Execution rename = execution(before, after);

		// What the attempt that died reserved, and the effect it got as far as.
		PreparedMovement reserved = explorerRelocationPlan.reserve(rename, before, after, false).getFirst();

		Files.writeString(after, "content");

		explorerRenameService.execute(rename, Takings.owning(rename.getId()));

		Assertions.assertThat(Files.exists(before)).as("no second move: there was nothing left to move").isFalse();
		Assertions.assertThat(Files.readString(after)).isEqualTo("content");

		Assertions.assertThat(currentPathOf(file)).isEqualTo(PathUtils.normalize(after));

		Assertions.assertThat(factsOf(file)).singleElement()
				.isEqualTo(Map.of("id", reserved.catalogFileEventPublicId(), "old", PathUtils.normalize(before),
						"new", PathUtils.normalize(after)));

		Assertions.assertThat(statusOf(file)).isEqualTo(MovementStatus.MOVED.name());
	}

	/**
	 * A folder is one execution and a fact per file, and the retry has to finish
	 * what is left of it rather than start again.
	 *
	 * <p>
	 * Half-written facts are not among the states it can find: the door that moves
	 * a folder records all of them or none, and refuses a batch where some
	 * identities are already on record and some are not - that is two operations
	 * wearing one name. What can be left half done is what comes after them, so
	 * this is the crash between the facts and the operations settling: the
	 * catalog already knows, and the run still has to say it finished.
	 */
	@Test
	void aFolderWhoseFactsWereWrittenButNotSettledIsFinishedNotRepeated(@TempDir Path library) throws IOException {
		Path before = Files.createDirectories(library.resolve("before"));
		Path after = library.resolve("after");

		appSettingService.update(SettingsConstants.WATCH_FOLDER, library.toString(), "system");

		CatalogFile first = CatalogFiles.catalogued(new TransactionTemplate(transactionManager), catalogFileRepository,
				catalogFileLocationRepository, before.resolve("a.jpg"));
		CatalogFile second = CatalogFiles.catalogued(new TransactionTemplate(transactionManager), catalogFileRepository,
				catalogFileLocationRepository, before.resolve("b.jpg"));

		Execution rename = execution(before, after);

		List<PreparedMovement> reserved = explorerRelocationPlan.reserve(rename, before, after, true);

		Assertions.assertThat(reserved).as("one operation per catalogued file under the folder").hasSize(2);

		// The attempt that died: the folder moved, its facts were written under the
		// identities reserved above, and it stopped before saying the operations were
		// done. From here the catalog no longer names anything under the old folder,
		// which is why the retry cannot work out what it was doing from the catalog.
		Files.move(before, after);

		catalogMutations.repointFolder(PathUtils.normalize(before), PathUtils.normalize(after),
				reserved.stream().map(PreparedMovement::catalogFileId).toList(),
				reserved.stream().map(PreparedMovement::catalogFileEventPublicId).toList(),
				new CatalogFactProvenance(Instant.now(), CatalogEventSources.EXPLORER,
						CatalogEventEvidence.NIMBUS_OPERATION, null));

		explorerRenameService.execute(rename, Takings.owning(rename.getId()));

		Assertions.assertThat(Files.exists(before)).isFalse();

		Assertions.assertThat(currentPathOf(first)).isEqualTo(PathUtils.normalize(after.resolve("a.jpg")));
		Assertions.assertThat(currentPathOf(second)).isEqualTo(PathUtils.normalize(after.resolve("b.jpg")));

		// One fact per file, each under the identity reserved for it - not a second
		// history written by the attempt that finished the first one's work.
		Assertions.assertThat(factsOf(first)).hasSize(1);
		Assertions.assertThat(factsOf(second)).hasSize(1);

		Assertions.assertThat(List.of(factsOf(first).getFirst().get("id"), factsOf(second).getFirst().get("id")))
				.containsExactlyInAnyOrderElementsOf(
						reserved.stream().map(PreparedMovement::catalogFileEventPublicId).toList());

		Assertions.assertThat(statusOf(first)).isEqualTo(MovementStatus.MOVED.name());
		Assertions.assertThat(statusOf(second)).isEqualTo(MovementStatus.MOVED.name());
	}

	@AfterEach
	void clean() {
		// This drives the real worker path, which commits: nothing here is undone
		// by a rollback, and the next case would meet it.
		jdbcTemplate.update("DELETE FROM movement");
		jdbcTemplate.update("DELETE FROM catalog_file_event");
		jdbcTemplate.update("DELETE FROM catalog_file");
		jdbcTemplate.update("DELETE FROM execution");
	}

	private Execution execution(Path source, Path target) {
		return executionRepository.saveAndFlush(Execution.builder().executionType(ExecutionType.EXPLORER_RENAME)
				.status(ExecutionStatus.RUNNING).startedAt(LocalDateTime.now()).executeFlag(true)
				.sourcePath(PathUtils.normalize(source)).targetPath(PathUtils.normalize(target)).build());
	}

	private String currentPathOf(CatalogFile file) {
		return jdbcTemplate.queryForObject("SELECT current_path FROM catalog_file_location WHERE catalog_file_id = ?",
				String.class, file.getId());
	}

	private String statusOf(CatalogFile file) {
		return jdbcTemplate.queryForObject("SELECT status FROM movement WHERE catalog_file_id = ?", String.class,
				file.getId());
	}

	private List<Map<String, Object>> factsOf(CatalogFile file) {
		return jdbcTemplate.queryForList("""
				SELECT catalog_file_event_public_id AS id, old_path AS old, new_path AS new
				FROM catalog_file_event WHERE catalog_file_id = ? ORDER BY id
				""", file.getId());
	}
}