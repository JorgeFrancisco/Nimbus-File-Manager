package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.ConversionLauncherService;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.VideoTranscoder;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.TranscodeResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.FingerprintAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisPayload;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.SimilarityRunMode;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.facade.MetadataFacade;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.model.MetadataResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantineRestoreLauncher;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreOptions;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.CatalogFactProvenance;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.CatalogLifecycleWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * A similarity run going all the way through the queue, against a real database
 * and a real worker.
 *
 * <p>
 * It exists for the reason the fingerprint backlog needed the same test: both
 * similarity types had unit tests, integration tests over their own services,
 * and a green build - and neither had ever been observed crossing the
 * dispatcher. Everything asserting "asked for, claimed, run, finished" was
 * asserting it against a mock, and a handler that refuses the payload the
 * product really writes would pass every one of those and fail the first real
 * run.
 *
 * <p>
 * The request is made through {@link SimilarityLauncher} rather than assembled
 * here, so what the worker claims is the payload the application really
 * enqueues - schema version, digests and mode included.
 *
 * <p>
 * <b>What each medium proves.</b> Photo carries the assertion all the way to
 * the published relations, because a photo pair can be made to look alike with
 * two rows. Video stops at the terminal status of the execution: making a video
 * pair relate takes frame and gate data whose shape is the subject of its own
 * tests, and asserting an empty result here would be asserting the absence of a
 * defect this test never characterised. What was missing for video was the
 * crossing itself, and that is what is asserted.
 *
 * <p>
 * Deliberately not {@code @Transactional}: the worker claims the row from
 * another thread and another transaction, so anything this test leaves
 * uncommitted is invisible to it. That is also why it keeps a container of its
 * own rather than sharing one.
 */
@SpringBootTest
@ActiveProfiles(NimbusProfiles.APP_WORKER_COMBINED)
@Testcontainers
class SimilarityExecutionEndToEndIntegrationTest {

	private static final int MINIMUM = 70;

	/** The same look for every photo, so the pair is found and not hoped for. */
	private static final long ALPHA = 101;
	private static final long NEARBY = 7;

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

	@Autowired
	private SimilarityLauncher similarityLauncher;

	@Autowired
	private DuplicateExclusionService duplicateExclusionService;

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private MediaFingerprintRepository mediaFingerprintRepository;

	@Autowired
	private ExecutionPayloadCodec executionPayloadCodec;

	@Autowired
	private DuplicateDeletionLauncherService duplicateDeletionLauncherService;

	@Autowired
	private QuarantineRestoreLauncher quarantineRestoreLauncher;

	@Autowired
	private AppSettingService appSettingService;

	@Autowired
	private SimilarityViewService similarityViewService;

	@Autowired
	private ConversionLauncherService conversionLauncherService;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private CatalogLifecycleWriter catalogLifecycleWriter;

	/**
	 * The encoder, and only the encoder. Everything the revival test is about -
	 * placing the file, cataloguing it, finding the entry the catalog had given up
	 * on, bringing it back and saying so - runs for real; what is replaced is
	 * ffmpeg, which needs an external binary the CI may not have and which has
	 * nothing to do with what is being proved.
	 */
	@MockitoBean
	private VideoTranscoder videoTranscoder;

	/**
	 * Reading a real video's streams is the other thing that needs a binary the CI
	 * may not have, and it is an input to the catalogue step rather than part of
	 * it: what is being proved is what the catalogue does with an entry it finds,
	 * not how the facts about the file were obtained.
	 */
	@MockitoBean
	private MetadataFacade metadataFacade;

	/**
	 * Real until a test says otherwise. It is the one seam where asking for a
	 * regroup can fail for reasons nothing in the mutation controls - the queue,
	 * the connection, the transaction - and the failure has to be injected there
	 * rather than staged somewhere convenient.
	 */
	@MockitoSpyBean
	private ExecutionEnqueueService executionEnqueueService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@AfterEach
	void empty() {
		jdbcTemplate.update("DELETE FROM similarity_group_member");
		jdbcTemplate.update("DELETE FROM similarity_group");
		jdbcTemplate.update("DELETE FROM similarity_grouping");
		jdbcTemplate.update("DELETE FROM similarity_relation_coverage");
		jdbcTemplate.update("DELETE FROM similarity_relation");
		jdbcTemplate.update("DELETE FROM media_fingerprint");
		jdbcTemplate.update("DELETE FROM conversion_item_result");
		jdbcTemplate.update("DELETE FROM movement");
		jdbcTemplate.update("DELETE FROM catalog_file");
		jdbcTemplate.update("DELETE FROM execution");

		// The one exclusion table nothing above reaches: file exclusions go with the
		// catalog rows they belong to, a hidden folder belongs to nobody and would
		// outlive the test that hid it.
		jdbcTemplate.update("DELETE FROM duplicate_folder_exclusion");

		// The quarantine root is a setting, and a setting outlives the test that wrote
		// it - a leftover temporary folder would answer "configured" for whoever runs
		// next, pointing at a directory that no longer exists.
		appSettingService.update(SettingsConstants.TRASH_FOLDER, "", "test");
	}

	/**
	 * The whole path for photos: asked for by the launcher, claimed by the worker,
	 * run as a rebuild, and what it found published where the screen reads it.
	 */
	@Test
	void aPhotoRebuildIsAskedForClaimedRunAndPublishesWhatItFound() {
		photo();
		photo();

		Execution finished = awaitTerminal(similarityLauncher.launchPhotos(MINIMUM).getId());

		assertThat(finished.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
		assertThat(finished.getClaimedBy()).as("a worker really took it").isNotNull();
		assertThat(finished.getClaimCount()).isEqualTo(1);

		assertThat(modeOf(finished)).isEqualTo(SimilarityRunMode.REBUILD);
		assertThat(relationCount()).as("the pair that looks alike").isEqualTo(1);
		assertThat(groupCount()).as("and a grouping the screen can read").isPositive();
	}

	/**
	 * An arrival is a different kind of work, and the difference has to survive the
	 * trip through the queue: what the worker claims must say ADD, because a
	 * rebuild of the whole library on every backup of a phone is the cost this mode
	 * exists to avoid.
	 */
	@Test
	void anArrivalIsClaimedAndRunAsAnAddRatherThanARebuild() {
		photo();
		photo();

		awaitTerminal(similarityLauncher.launchPhotos(MINIMUM).getId());

		photo();

		// What the fingerprint handler calls when a drain wrote something, rather
		// than addPhotos: the decision of which thresholds are worth an arrival is
		// part of what is under test.
		similarityLauncher.refreshPhotosAfterArrival();

		Execution finished = awaitTerminalOf(SimilarityRunMode.ADD);

		assertThat(finished.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
		assertThat(finished.getClaimedBy()).isNotNull();

		assertThat(modeOf(finished)).isEqualTo(SimilarityRunMode.ADD);
		assertThat(relationCount()).as("the newcomer relates to both of them").isEqualTo(3);
	}

	/**
	 * <b>The reachability this class exists to prove.</b> Nothing here mentions
	 * REGROUP: it excludes a file the way the screen does, and everything after
	 * that has to happen on its own - the event, the listener, the launcher, the
	 * queue, the worker, and a run that regroups rather than rebuilds.
	 *
	 * <p>
	 * A test that built the payload by hand would have passed against the code as
	 * it was before this wiring existed, when no production path could reach
	 * REGROUP at all. That is the difference this asserts.
	 */
	@Test
	void excludingAFileTheWayTheScreenDoesEndsInARegroupThatKeepsTheRelations() {
		photo();

		CatalogFile excluded = photo();

		awaitTerminal(similarityLauncher.launchPhotos(MINIMUM).getId());

		int relationsBefore = relationCount();

		assertThat(duplicateExclusionService.excludeFile(excluded.getCatalogFilePublicId())).isTrue();

		Execution finished = awaitTerminalOf(SimilarityRunMode.REGROUP);

		assertThat(finished.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
		assertThat(finished.getClaimedBy()).as("a worker really took it").isNotNull();

		assertThat(modeOf(finished)).isEqualTo(SimilarityRunMode.REGROUP);
		assertThat(relationCount()).as("exclusion is not deletion: what was computed is still stored")
				.isEqualTo(relationsBefore);
	}

	/**
	 * The same reachability for the other half of the exclusion screen. Hiding a
	 * folder is a second publish in the same service, and it could have been left
	 * unwired without a single test noticing - the file case above would go on
	 * passing, and a user who hid a folder would be told the analysis was current
	 * while it was computed over files that are no longer allowed in it.
	 */
	@Test
	void excludingAFolderTheWayTheScreenDoesEndsInARegroupToo(@TempDir Path hidden) {
		photo();
		photo();

		awaitTerminal(similarityLauncher.launchPhotos(MINIMUM).getId());

		int relationsBefore = relationCount();

		assertThat(duplicateExclusionService.excludeFolder(hidden.toString())).isTrue();

		Execution finished = awaitTerminalOf(SimilarityRunMode.REGROUP);

		assertThat(finished.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
		assertThat(finished.getClaimedBy()).as("a worker really took it").isNotNull();

		assertThat(modeOf(finished)).isEqualTo(SimilarityRunMode.REGROUP);
		assertThat(relationCount()).as("hiding a folder is not deletion either").isEqualTo(relationsBefore);
	}

	/**
	 * Video across the dispatcher, which is the half that had never been observed.
	 * See the class comment for why this stops at the status.
	 */
	@Test
	void aVideoRunIsAskedForClaimedAndReachesATerminalStatus() {
		video();
		video();

		Execution finished = awaitTerminal(similarityLauncher.launchVideos(MINIMUM).getId());

		assertThat(finished.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
		assertThat(finished.getClaimedBy()).isNotNull();
		assertThat(finished.getClaimCount()).isEqualTo(1);

		assertThat(modeOf(finished)).isEqualTo(SimilarityRunMode.REBUILD);
	}

	/**
	 * The regroup is asked for after the commit, so an exclusion that never
	 * commits asks for nothing. Without that ordering the queue would carry work
	 * about a world that was rolled back before anyone saw it.
	 *
	 * <p>
	 * Asserted with no waiting because none is needed: the listener runs on the
	 * committing thread, so by the time the call returns the request has either
	 * been made or never will be.
	 */
	@Test
	void anExclusionThatRolledBackAsksForNothing() {
		photo();

		CatalogFile doomed = photo();

		awaitTerminal(similarityLauncher.launchPhotos(MINIMUM).getId());

		Long before = newestOf(SimilarityRunMode.REGROUP);

		assertThatThrownBy(() -> excludeAndThenFail(doomed)).isInstanceOf(IllegalStateException.class);

		assertThat(newestOf(SimilarityRunMode.REGROUP)).as("nothing committed, so there is nothing to regroup")
				.isEqualTo(before);
	}

	/**
	 * A family nobody ever analysed is not brought into existence by an exclusion:
	 * the first analysis costs minutes and is a decision the user makes.
	 */
	@Test
	void anExclusionBeforeAnyAnalysisAsksForNothing() {
		CatalogFile never = photo();

		assertThat(duplicateExclusionService.excludeFile(never.getCatalogFilePublicId())).isTrue();

		assertThat(newestOf(SimilarityRunMode.REGROUP)).as("no analysed threshold, no regroup").isNull();
	}

	/**
	 * <b>The whole soft-delete round trip, through the queue in both directions.</b>
	 *
	 * <p>
	 * Nothing here mentions REGROUP either. It quarantines a duplicate the way the
	 * Duplicados screen does - a request the worker claims and carries out - and
	 * then puts it back the way the Quarentena screen does, which is a second
	 * request the worker claims. Each of the two has to produce its own regroup on
	 * its own, and the second one is the reason this test exists: the event that
	 * asks for it is published <em>inside the worker</em>, after a commit that
	 * happened on a worker thread, which is a place nothing had ever been observed
	 * publishing from.
	 *
	 * <p>
	 * The relation is counted at every step and never changes. That is the claim a
	 * regroup makes and a rebuild does not: quarantining a file and bringing it
	 * back changes who takes part, not how alike any two of them are, so the
	 * comparison that cost the minutes is answered from what is already stored.
	 */
	@Test
	void quarantiningAndRestoringThroughTheWorkerEachEndInARegroupThatReusesTheRelations(@TempDir Path library,
			@TempDir Path quarantine) throws IOException {
		appSettingService.update(SettingsConstants.TRASH_FOLDER, quarantine.toString(), "test");

		photoOnDisk(library);

		CatalogFile removed = photoOnDisk(library);

		awaitTerminal(similarityLauncher.launchPhotos(MINIMUM).getId());

		int relations = relationCount();

		assertThat(relations).as("the pair that looks alike").isEqualTo(1);

		Long beforeQuarantine = newestOf(SimilarityRunMode.REGROUP);

		duplicateDeletionLauncherService.launch(List.of(removed.getCatalogFilePublicId()));

		awaitTerminal(newestOfType(ExecutionType.DEDUP_DELETE));

		Execution afterQuarantine = awaitTerminalAfter(SimilarityRunMode.REGROUP, beforeQuarantine);

		assertThat(afterQuarantine.getClaimedBy()).as("a worker really took the regroup").isNotNull();
		assertThat(lifecycleOf(removed)).isEqualTo(LifecycleStatus.DELETED);
		assertThat(relationCount()).as("a soft delete hides a file; it does not unsay what was computed")
				.isEqualTo(relations);

		UUID movement = quarantinedMovementOf(removed);

		quarantineRestoreLauncher.restore(movement, QuarantineRestoreOptions.defaults());

		Execution restore = awaitTerminal(newestOfType(ExecutionType.QUARANTINE_RESTORE));

		assertThat(restore.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
		assertThat(lifecycleOf(removed)).as("the file is back in the catalog").isEqualTo(LifecycleStatus.ACTIVE);

		Execution afterRestore = awaitTerminalAfter(SimilarityRunMode.REGROUP, afterQuarantine.getId());

		assertThat(afterRestore.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
		assertThat(afterRestore.getClaimedBy()).as("asked for from inside the worker, and claimed there").isNotNull();

		assertThat(modeOf(afterRestore)).isEqualTo(SimilarityRunMode.REGROUP);
		assertThat(relationCount()).as("the pair was never compared again: the stored relation is reused")
				.isEqualTo(relations);
		assertThat(groupCount()).as("and the grouping the screen reads was published again").isPositive();
	}

	/**
	 * A catalogued photo that is also a file on disk, which the quarantine path
	 * needs: it moves bytes, verifies them and refuses anything that is not a
	 * physical file, so a row naming a path nobody wrote would be skipped rather
	 * than quarantined.
	 */
	private CatalogFile photoOnDisk(Path library) throws IOException {
		Path file = Files.createFile(library.resolve("similarity-e2e-" + System.nanoTime() + ".jpg"));

		Files.writeString(file, "photo " + file.getFileName());

		CatalogFile stored = CatalogFile.builder()
				.extension("jpg").sizeBytes(Files.size(file))
				.modifiedAt(Instant.now()).fileType(FileType.PHOTO).build();

		stored.setLocation(CatalogFileLocation.builder().catalogFile(stored).currentPath(PathUtils.normalize(file))
				.currentFolder(PathUtils.normalize(library))
				.pathFlavor(PathFlavor.WINDOWS).build());

		CatalogFile saved = CatalogFiles.catalogued(new TransactionTemplate(transactionManager),
				catalogFileRepository, catalogFileLocationRepository, stored);

		mediaFingerprintRepository.saveAndFlush(MediaFingerprint.builder().catalogFileId(saved.getId())
				.kind(FingerprintKind.PHOTO_PHASH).algorithm(FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1)
				.sampleIndex(0).hashBytes(PhotoSimilarityLibrary.hash(NEARBY, (int) (saved.getId() % 16)))
				.sampleBytes(PhotoSimilarityLibrary.sample(ALPHA, 0)).computedAt(LocalDateTime.now()).build());

		return saved;
	}

	private LifecycleStatus lifecycleOf(CatalogFile file) {
		return LifecycleStatus.valueOf(jdbcTemplate.queryForObject(
				"SELECT lifecycle_status FROM catalog_file WHERE id = ?", String.class, file.getId()));
	}

	/** The row the Quarentena screen would offer a restore for. */
	private UUID quarantinedMovementOf(CatalogFile file) {
		return jdbcTemplate.queryForObject("SELECT movement_public_id FROM movement WHERE catalog_file_id = ?"
				+ " AND status = 'MOVED' ORDER BY id DESC LIMIT 1", UUID.class, file.getId());
	}

	private Long newestOfType(ExecutionType type) {
		return await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250))
				.until(() -> jdbcTemplate
						.queryForList("SELECT id FROM execution WHERE execution_type = ? ORDER BY id DESC LIMIT 1",
								Long.class, type.name())
						.stream().findFirst().orElse(null), Objects::nonNull);
	}

	/**
	 * Waits for a run of {@code mode} newer than one already seen. Two operations
	 * in a row ask for the same kind of work, and the deduplication key of a
	 * regroup names no snapshot - so "the newest one" is the same row until the
	 * second request has been written, and asserting against it would be asserting
	 * about the first.
	 */
	private Execution awaitTerminalAfter(SimilarityRunMode mode, Long seen) {
		Long id = await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250))
				.until(() -> newestOf(mode), newest -> newest != null && !newest.equals(seen));

		return awaitTerminal(id);
	}

	/**
	 * <b>The safety net, with the net actually needed.</b>
	 *
	 * <p>
	 * The exclusion is real and so is the failure: once the analysis has been
	 * published, queueing is made to fail with the very message the transactional
	 * defect produced - a regroup asked for after a commit, on a transaction that
	 * had finished. What must not happen is that this reaches the person who
	 * excluded the file. The exclusion happened; only the request to bring the
	 * answer up to date did not.
	 *
	 * <p>
	 * And nothing is silently wrong afterwards. The exclusion list is part of what
	 * identifies an analysis, so the answer published before it belongs to a
	 * different family and stops being served as one about this library at all -
	 * which is the loudest form the safety net takes, and the one the user can act
	 * on with a rebuild.
	 */
	@Test
	void anEnqueueThatFailsLeavesTheExclusionStandingAndTheAnswerNoLongerServed() {
		photo();

		CatalogFile excluded = photo();

		awaitTerminal(similarityLauncher.launchPhotos(MINIMUM).getId());

		assertThat(similarityViewService.outdated(ExecutionType.SIMILARITY_PHOTO, MINIMUM).orElseThrow())
				.as("the answer describes the library as it is").isFalse();

		doThrow(new IllegalStateException("no transaction is in progress")).when(executionEnqueueService)
				.enqueueOrExisting(any());

		assertThat(duplicateExclusionService.excludeFile(excluded.getCatalogFilePublicId()))
				.as("the mutation is not turned into a failure by what happens after it").isTrue();

		assertThat(duplicateExclusionService.excludedFilePublicIds())
				.containsExactly(excluded.getCatalogFilePublicId());
		assertThat(newestOf(SimilarityRunMode.REGROUP)).as("nothing was queued, which is the point").isNull();

		assertThat(similarityViewService.photos(MINIMUM, PageRequest.of(0, 10)).published())
				.as("the answer published before the exclusion is not offered as an answer about this library")
				.isFalse();
	}

	/**
	 * The other half of the net, for the changes that leave the analysis's identity
	 * alone.
	 *
	 * <p>
	 * A file leaving the active set does not change which analysis this is - the
	 * family is the medium, the algorithm and the parameters, and none of them
	 * moved - so the published answer goes on being served. What catches it is the
	 * composition: the set that would be analysed now is not the set that was, and
	 * the screen says so.
	 *
	 * <p>
	 * Written straight to the column on purpose. It is the shape of a mutation path
	 * that nothing announces - the state this whole slice exists to remove, and the
	 * one the digest has to survive if a future one is ever missed.
	 */
	@Test
	void aLifecycleChangeNobodyAnnouncedIsStillCaughtByTheDigest() {
		photo();

		CatalogFile vanished = photo();

		awaitTerminal(similarityLauncher.launchPhotos(MINIMUM).getId());

		assertThat(similarityViewService.outdated(ExecutionType.SIMILARITY_PHOTO, MINIMUM).orElseThrow()).isFalse();

		jdbcTemplate.update("UPDATE catalog_file SET lifecycle_status = 'DELETED' WHERE id = ?", vanished.getId());

		assertThat(similarityViewService.outdated(ExecutionType.SIMILARITY_PHOTO, MINIMUM).orElseThrow())
				.as("the answer is still served, and it is served as one the library has moved past").isTrue();
	}

	/**
	 * An exclusion that is written and then lost, in one transaction. A method of
	 * its own so the assertion above watches a single call: the point is what the
	 * whole unit of work did, not which of its two statements raised.
	 */
	private void excludeAndThenFail(CatalogFile doomed) {
		new TransactionTemplate(transactionManager).executeWithoutResult(_ -> {
			duplicateExclusionService.excludeFile(doomed.getCatalogFilePublicId());

			throw new IllegalStateException("the operation failed after the exclusion was written");
		});
	}

	/**
	 * <b>The last unwired path, proved through the product rather than argued.</b>
	 *
	 * <p>
	 * A conversion writes its output next to the source and catalogues it. Delete
	 * that output from outside the application, let the catalog give up on it, and
	 * convert the same original again: the second run lands on the same path, finds
	 * the entry that was marked missing and brings it back to life. That is a file
	 * rejoining the set a duplicate analysis may look at, and nothing about it is a
	 * quarantine - the original is kept, which is what the default disposition
	 * says, so the announcement can only be coming from the revival.
	 *
	 * <p>
	 * Everything between the request and the revival is the product: the queue, the
	 * worker, the placement, the catalogue step, the persistence and the mapper.
	 * Only the encoder is replaced, because it shells out to ffmpeg and has nothing
	 * to do with the claim. Nothing here publishes an event, builds a payload or
	 * calls {@code markActive}.
	 *
	 * <p>
	 * <b>Why the regroup is a photo one.</b> A family is brought up to date only if
	 * it has an answer to bring - which means stored relations - and making a video
	 * pair relate takes frame data whose shape is the subject of its own tests. So
	 * the photos carry the analysed family and the video carries the revival, and
	 * each assertion is made where it is real: the regroup is observed on the
	 * family that had an answer, and the revived entry is observed against the
	 * query that decides eligibility itself.
	 */
	@Test
	void aConversionThatRevivesItsOwnMissingOutputEndsInARegroup(@TempDir Path library, @TempDir Path workspace)
			throws IOException {
		photo();
		photo();

		awaitTerminal(similarityLauncher.launchPhotos(MINIMUM).getId());

		int relations = relationCount();

		assertThat(relations).isEqualTo(1);

		CatalogFile original = videoOnDisk(library);

		Path output = library.resolve(original.getLocation().fileName().replace(".mp4", "_H265.mp4"));

		encodes(workspace, output);

		convertThroughTheWorker(original);

		CatalogFile produced = catalogFileLocationRepository
				.findPresentByPath(PathUtils.normalize(output), PathFlavor.current().name()).orElseThrow();

		assertThat(newestOf(SimilarityRunMode.REGROUP))
				.as("an entry new to the catalog joins by the backlog, not by a regroup").isNull();

		// What the backlog would have written for it. Without a fingerprint the entry
		// cannot be in the eligible set at all, and the assertions below would be
		// about nothing.
		fingerprint(produced);

		assertThat(eligibleVideoIds()).contains(produced.getId());

		Files.delete(output);

		// The walk found nothing at the path it expected, which is the whole of what
		// this fact says and the only thing that took the file out of the analysed set.
		catalogLifecycleWriter.markMissing(List.of(produced.getId()), new CatalogFactProvenance(Instant.now(),
				CatalogEventSources.RECONCILE, CatalogEventEvidence.PATH_NOT_FOUND, null));

		assertThat(lifecycleOf(produced)).isEqualTo(LifecycleStatus.MISSING);
		assertThat(eligibleVideoIds()).as("a missing file takes no part in an analysis")
				.doesNotContain(produced.getId());

		encodes(workspace, output);

		convertThroughTheWorker(original);

		assertThat(lifecycleOf(produced)).as("the second conversion found the entry and brought it back")
				.isEqualTo(LifecycleStatus.ACTIVE);
		assertThat(lifecycleOf(original)).as("and the original was kept, so no quarantine explains the announcement")
				.isEqualTo(LifecycleStatus.ACTIVE);
		assertThat(eligibleVideoIds()).contains(produced.getId());

		Execution regroup = awaitTerminalAfter(SimilarityRunMode.REGROUP, null);

		assertThat(regroup.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
		assertThat(regroup.getClaimedBy()).as("a worker really took it").isNotNull();

		assertThat(modeOf(regroup)).isEqualTo(SimilarityRunMode.REGROUP);
		assertThat(relationCount()).as("a file coming back is not a reason to compare anything again")
				.isEqualTo(relations);
	}

	/** The request the Conversao screen makes, carried out by a real worker. */
	private void convertThroughTheWorker(CatalogFile original) {
		Long before = newestOfTypeOrNull(ExecutionType.CONVERSION);

		conversionLauncherService.launch(List.of(original.getCatalogFilePublicId()), null);

		Long queued = await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250))
				.until(() -> newestOfTypeOrNull(ExecutionType.CONVERSION),
						newest -> newest != null && !newest.equals(before));

		assertThat(awaitTerminal(queued).getStatus()).isEqualTo(ExecutionStatus.FINISHED);
	}

	/**
	 * An encode that produces a real file, since what happens to it next - a
	 * verified move into the library - reads its bytes.
	 */
	private void encodes(Path workspace, Path output) throws IOException {
		Path encoded = Files.writeString(workspace.resolve("encoded-" + System.nanoTime() + ".mp4"),
				"converted bytes for " + output.getFileName());

		when(videoTranscoder.transcode(any(), any(), any(), any()))
				.thenReturn(TranscodeResult.converted(encoded, false, false, false, 1_000));

		// Answered for the placed file by name rather than from the argument: the
		// watcher notices the new file too and asks about paths of its own, and what
		// this test is arranging is the one read the catalogue step makes.
		when(metadataFacade.extract(any(), any(), any())).thenReturn(facts(output));
	}

	/** What reading the placed file would have said about it. */
	private MetadataResult facts(Path file) {
		return MetadataResult.builder().fileName(file.getFileName().toString()).extension("mp4").sizeBytes(1L)
				.mimeType("video/mp4").fileType(FileType.VIDEO).subcategory(MediaSubcategory.OTHER)
				.createdAt(Instant.now()).modifiedAt(Instant.now()).build();
	}

	/** A catalogued video that is also a file on disk, which a conversion needs. */
	private CatalogFile videoOnDisk(Path library) throws IOException {
		Path file = Files.createFile(library.resolve("similarity-e2e-" + System.nanoTime() + ".mp4"));

		Files.writeString(file, "source bytes");

		CatalogFile stored = CatalogFile.builder()
				.extension("mp4").sizeBytes(Files.size(file))
				.modifiedAt(Instant.now()).fileType(FileType.VIDEO).build();

		stored.setLocation(CatalogFileLocation.builder().catalogFile(stored).currentPath(PathUtils.normalize(file))
				.currentFolder(PathUtils.normalize(library))
				.pathFlavor(PathFlavor.WINDOWS).build());

		return CatalogFiles.catalogued(new TransactionTemplate(transactionManager), catalogFileRepository,
				catalogFileLocationRepository, stored);
	}

	private void fingerprint(CatalogFile video) {
		mediaFingerprintRepository.saveAndFlush(MediaFingerprint.builder().catalogFileId(video.getId())
				.kind(FingerprintKind.VIDEO_PHASH)
				.algorithm(FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_FRAMES_V1).sampleIndex(0)
				.hashBytes(new byte[32]).sampleBytes(new byte[1024]).computedAt(LocalDateTime.now()).build());
	}

	/** The query that decides who takes part, asked as the analysis asks it. */
	private List<Long> eligibleVideoIds() {
		return mediaFingerprintRepository.findEligibleForSimilarity(FingerprintKind.VIDEO_PHASH.name(),
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_FRAMES_V1);
	}

	private Long newestOfTypeOrNull(ExecutionType type) {
		return jdbcTemplate
				.queryForList("SELECT id FROM execution WHERE execution_type = ? ORDER BY id DESC LIMIT 1", Long.class,
						type.name())
				.stream().findFirst().orElse(null);
	}

	private CatalogFile photo() {
		CatalogFile file = catalogued("jpg", FileType.PHOTO);

		mediaFingerprintRepository.saveAndFlush(MediaFingerprint.builder().catalogFileId(file.getId())
				.kind(FingerprintKind.PHOTO_PHASH).algorithm(FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1)
				.sampleIndex(0).hashBytes(PhotoSimilarityLibrary.hash(NEARBY, (int) (file.getId() % 16)))
				.sampleBytes(PhotoSimilarityLibrary.sample(ALPHA, 0)).computedAt(LocalDateTime.now()).build());

		return file;
	}

	private long video() {
		CatalogFile file = catalogued("mp4", FileType.VIDEO);

		mediaFingerprintRepository.saveAndFlush(MediaFingerprint.builder().catalogFileId(file.getId())
				.kind(FingerprintKind.VIDEO_PHASH)
				.algorithm(FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_FRAMES_V1).sampleIndex(0)
				.hashBytes(new byte[32]).sampleBytes(new byte[1024]).computedAt(LocalDateTime.now()).build());

		return file.getId();
	}

	private CatalogFile catalogued(String extension, FileType fileType) {
		return catalogFileRepository.saveAndFlush(CatalogFile.builder()
				.extension(extension).sizeBytes(1L)
				.modifiedAt(Instant.now()).fileType(fileType).build());
	}

	private SimilarityRunMode modeOf(Execution execution) {
		return executionPayloadCodec.decode(execution.getRequestPayload(), SimilarityAnalysisPayload.class).mode();
	}

	private int relationCount() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM similarity_relation", Integer.class);
	}

	private int groupCount() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM similarity_group", Integer.class);
	}

	/**
	 * Waits for a run of {@code mode} to exist and then to finish. The id is not
	 * known to the caller on purpose: an arrival and a regroup are asked for by
	 * something other than the test, and being handed the row would skip the very
	 * step being proved.
	 *
	 * <p>
	 * Found by the tail of the deduplication key, which is the mode for exactly
	 * these two and a composition digest for a rebuild - so this can never pick up
	 * the rebuild that ran first.
	 */
	private Execution awaitTerminalOf(SimilarityRunMode mode) {
		Long id = await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250))
				.until(() -> newestOf(mode), Objects::nonNull);

		return awaitTerminal(id);
	}

	private Long newestOf(SimilarityRunMode mode) {
		return jdbcTemplate
				.queryForList("SELECT id FROM execution WHERE dedup_key LIKE ? ORDER BY id DESC LIMIT 1", Long.class,
						"%:" + mode)
				.stream().findFirst().orElse(null);
	}

	private Execution awaitTerminal(Long executionId) {
		return await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).until(
				() -> executionRepository.findById(executionId).orElseThrow(),
				execution -> execution.getStatus().isTerminal());
	}
}