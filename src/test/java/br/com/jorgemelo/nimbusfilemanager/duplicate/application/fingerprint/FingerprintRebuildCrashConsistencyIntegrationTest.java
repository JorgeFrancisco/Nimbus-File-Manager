package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.RelationParameters;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintFailureReason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.FingerprintFailure;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintFailureRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintRebuildTaskRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PendingPhoto;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence.SimilarityRelationWriter;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnershipGuard;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionMetricsContext;

/**
 * A rebuild interrupted anywhere, and the library that survives it.
 *
 * <p>
 * This is the property the whole slice exists for. A rebuild used to begin by
 * deleting every fingerprint of its kind, because "still to do" meant "has no
 * row"; a run interrupted after that left the library without an entire
 * algorithm for as long as recomputing took, and every consumer read the remains
 * as the truth. Now the debt is written down and each file is replaced by its
 * own short transaction, so an interruption leaves a library that is part old
 * and part new - never one that is empty.
 *
 * <p>
 * Nothing is killed and nothing is slept on. Each test stops at a transaction
 * boundary and reads PostgreSQL back, which is the same state a process that
 * died there would have left. What continues afterwards is a different execution
 * with a different id, carrying no memory of the one before it.
 */
@SpringBootTest
@Testcontainers
class FingerprintRebuildCrashConsistencyIntegrationTest {

	private static final String WORKER = "worker-that-came-back";

	private static final String ALGORITHM = DuplicateConstants.ALGORITHM;

	private static final RelationParameters RELATIONS = new RelationParameters(ALGORITHM, 8, 90);

	private static final int WHOLE_BATCH = 200;

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

	@Autowired
	private PhashBacklogService phashBacklogService;

	@Autowired
	private FingerprintBacklogEngine engine;

	@Autowired
	private FingerprintBacklogLauncher launcher;

	@Autowired
	private FingerprintRebuildTaskRepository taskRepository;

	@Autowired
	private MediaFingerprintRepository mediaFingerprintRepository;

	@Autowired
	private FingerprintFailureRepository fingerprintFailureRepository;

	@Autowired
	private SimilarityRelationWriter similarityRelationWriter;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private ExecutionOwnershipGuard executionOwnershipGuard;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void forgetEverything() {
		jdbcTemplate.update("DELETE FROM similarity_relation_coverage");
		jdbcTemplate.update("DELETE FROM similarity_relation");

		taskRepository.deleteAll();
		mediaFingerprintRepository.deleteAll();
		fingerprintFailureRepository.deleteAll();
		executionRepository.deleteAll();
		catalogFileRepository.deleteAll();
	}

	/**
	 * The state a process that died right after preparing would leave: the debt is
	 * written down and not one thing has been taken away.
	 */
	@Test
	void aSeedThatCommittedAndDrainedNothingHasTakenNothingAway() {
		long first = catalogued("first");
		long second = catalogued("second");

		fingerprinted(first, 1);
		fingerprinted(second, 1);
		related(first, second);
		failed(first, PhashBacklogService.MAX_ATTEMPTS);

		phashBacklogService.seedRebuild(current());

		assertThat(mediaFingerprintRepository.count()).as("the library still answers exactly as it did").isEqualTo(2);
		assertThat(relations()).as("and so does everything drawn from it").isEqualTo(1);
		assertThat(coverageOf(first)).isEqualTo(1);

		FingerprintFailure failure = fingerprintFailureRepository.findAll().getFirst();

		assertThat(failure.getAttempts()).as("only the budget moved").isZero();
		assertThat(failure.getReason()).isEqualTo(FingerprintFailureReason.UNKNOWN);
		assertThat(failure.getLastError()).isEqualTo("boom");

		assertThat(taskRepository.countByKindAndAlgorithm(PhashBacklogService.KIND, ALGORITHM)).isEqualTo(2);
		assertThat(phashBacklogService.status().pending()).as("pending is what is owed").isEqualTo(2);
	}

	/**
	 * Stopped after some of the work: the library is part old and part new, which
	 * is the whole point - the old part is still a usable answer.
	 */
	@Test
	void aRebuildStoppedHalfWayLeavesAMixedLibraryAndNeverAnEmptyOne() {
		long done = catalogued("done");
		long owed = catalogued("owed");

		fingerprinted(done, 1);
		fingerprinted(owed, 1);
		related(done, owed);

		phashBacklogService.seedRebuild(current());

		drain(current(), chunkOver(List.of(done)));

		assertThat(computedAtOf(done)).as("the processed file holds a new answer").isNotEqualTo(theOldInstant());
		assertThat(coverageOf(done)).as("and must be compared again").isZero();
		assertThat(owes(done)).as("its debt is settled").isFalse();

		assertThat(computedAtOf(owed)).as("the untouched file still holds the old one").isEqualTo(theOldInstant());
		assertThat(coverageOf(owed)).as("with its coverage intact").isEqualTo(1);
		assertThat(owes(owed)).as("and its debt outstanding").isTrue();

		assertThat(mediaFingerprintRepository.count()).as("nothing was ever missing").isEqualTo(2);
		assertThat(remaining()).as("what is left owed is exactly what is left to do").containsExactly(owed);
	}

	/**
	 * The proof that justifies keying the list by the target rather than by the
	 * execution that asked. A different execution, with no knowledge of the first,
	 * picks the work up from the database alone.
	 */
	@Test
	void anExecutionThatKnowsNothingOfTheFirstFinishesTheRebuild() {
		long done = catalogued("done");
		long owed = catalogued("owed");

		fingerprinted(done, 1);
		fingerprinted(owed, 1);

		long firstExecution = claimedAt(1);

		phashBacklogService.seedRebuild(Takings.fenced(firstExecution, WORKER, 1, executionOwnershipGuard));

		drain(Takings.fenced(firstExecution, WORKER, 1, executionOwnershipGuard), chunkOver(List.of(done)));

		LocalDateTime whenDoneWasReplaced = computedAtOf(done);

		// A different row entirely, as a restart would claim: no seed, no payload from
		// the one before it, nothing but what the database still owes.
		long laterExecution = claimedAt(1);

		assertThat(laterExecution).isNotEqualTo(firstExecution);

		List<Long> adopted = phashBacklogService.fetchPendingBatch(WHOLE_BATCH).stream()
				.map(PendingPhoto::catalogFileId).toList();

		assertThat(adopted).as("it reads what is still owed, and only that").containsExactly(owed);

		drain(Takings.fenced(laterExecution, WORKER, 1, executionOwnershipGuard), chunkOver(adopted));

		assertThat(taskRepository.count()).as("the rebuild is finished").isZero();
		assertThat(phashBacklogService.rebuildIsOpen()).isFalse();
		assertThat(computedAtOf(done)).as("and the file the first run finished was not done twice")
				.isEqualTo(whenDoneWasReplaced);
		assertThat(computedAtOf(owed)).isNotEqualTo(theOldInstant());
	}

	/**
	 * The same worker one attempt later. The taking that was replaced cannot
	 * settle a debt or overwrite an answer, and the one that took over carries on
	 * from what is persisted - no repair of the list is needed anywhere.
	 */
	@Test
	void aTakingThatLostTheRowLeavesTheWorkForTheOneThatHasIt() {
		long done = catalogued("done");
		long owed = catalogued("owed");

		fingerprinted(done, 1);
		fingerprinted(owed, 1);

		long executionId = claimedAt(1);

		ExecutionOwnership first = Takings.fenced(executionId, WORKER, 1, executionOwnershipGuard);

		phashBacklogService.seedRebuild(first);

		drain(first, chunkOver(List.of(done)));

		takenAgainAt(executionId, 2);

		drain(first, chunkOver(List.of(owed)));

		assertThat(computedAtOf(owed)).as("the replaced taking wrote nothing").isEqualTo(theOldInstant());
		assertThat(owes(owed)).as("and settled nothing").isTrue();

		ExecutionOwnership second = Takings.fenced(executionId, WORKER, 2, executionOwnershipGuard);

		drain(second, chunkOver(List.of(owed)));

		assertThat(computedAtOf(owed)).as("the taking that holds the row carries on").isNotEqualTo(theOldInstant());
		assertThat(taskRepository.count()).isZero();
	}

	/**
	 * The last file, and the boundary that decides when a rebuild is over. There
	 * is no instant at which the result is written and the debt still stands, nor
	 * one at which the debt is gone and the result is not: they are the same
	 * commit.
	 */
	@Test
	void theRebuildIsOverExactlyWhenTheLastDebtIsSettled() {
		long last = catalogued("last");

		fingerprinted(last, 1);

		phashBacklogService.seedRebuild(current());

		assertThat(taskRepository.count()).as("one debt left").isEqualTo(1);
		assertThat(phashBacklogService.rebuildIsOpen()).isTrue();

		drain(current(), chunkOver(List.of(last)));

		assertThat(taskRepository.count()).isZero();
		assertThat(phashBacklogService.rebuildIsOpen()).as("and it is over").isFalse();
		assertThat(computedAtOf(last)).isNotEqualTo(theOldInstant());
	}

	/**
	 * A rebuild finishes even when files cannot be read. What it promises is that
	 * every candidate got an outcome, not that every candidate got a new hash -
	 * and the old hash of an unreadable file is still the best answer there is.
	 */
	@Test
	void filesThatCannotBeReadDoNotKeepTheRebuildOpenForEver() {
		long readable = catalogued("readable");
		long unreadable = catalogued("unreadable");
		long flaky = catalogued("flaky");

		fingerprinted(readable, 1);
		fingerprinted(unreadable, 1);
		fingerprinted(flaky, 1);

		phashBacklogService.seedRebuild(current());

		drain(current(), chunk().succeedsWith(readable, 1).failsTerminally(unreadable).failsRetryably(flaky));

		assertThat(owes(unreadable)).as("a file no retry would answer is settled").isFalse();
		assertThat(computedAtOf(unreadable)).as("and keeps the answer it had").isEqualTo(theOldInstant());

		assertThat(owes(flaky)).as("a file with attempts left is still owed").isTrue();
		assertThat(computedAtOf(flaky)).isEqualTo(theOldInstant());

		// The retries a later run would make, until the budget is gone.
		drain(current(), chunk().failsRetryably(flaky));
		drain(current(), chunk().failsRetryably(flaky));

		assertThat(fingerprintFailureRepository.findByCatalogFileIdAndKindAndAlgorithm(flaky,
				PhashBacklogService.KIND, ALGORITHM).orElseThrow().getAttempts())
				.isEqualTo(PhashBacklogService.MAX_ATTEMPTS);
		assertThat(taskRepository.count()).as("every candidate has had an outcome, so the rebuild is over").isZero();
		assertThat(mediaFingerprintRepository.count()).as("and nothing lost its fingerprint on the way").isEqualTo(3);
	}

	/**
	 * A file the catalog loses sight of mid-rebuild is a debt nobody can pay.
	 * Dropping it is all that happens, and it is what lets the rebuild close.
	 */
	@Test
	void aFileThatGoesMissingDoesNotKeepTheRebuildOpen() {
		long stays = catalogued("stays");
		long goes = catalogued("goes");

		fingerprinted(stays, 1);
		fingerprinted(goes, 1);
		related(stays, goes);

		phashBacklogService.seedRebuild(current());

		wentMissing(goes);

		drain(current(), chunkOver(List.of(stays)));

		assertThat(taskRepository.count()).as("the drain settles what it can and drops what it cannot").isZero();
		assertThat(phashBacklogService.rebuildIsOpen()).isFalse();

		assertThat(computedAtOf(goes)).as("the missing file keeps its fingerprint").isEqualTo(theOldInstant());

		CatalogFile back = catalogFileRepository.findById(goes).orElseThrow();

		back.setLifecycleStatus(LifecycleStatus.ACTIVE);

		catalogFileRepository.saveAndFlush(back);

		assertThat(taskRepository.count()).as("coming back writes no debt of its own").isZero();
		assertThat(phashBacklogService.fetchPendingBatch(WHOLE_BATCH))
				.as("and the ordinary rule sees nothing to do, because it has a fingerprint").isEmpty();
	}

	/**
	 * Asking again in the middle. It means "recompute everything, again", so what
	 * was already done is owed once more - and it is still one list, holding one
	 * debt per file.
	 */
	@Test
	void askingForTheWholeRebuildAgainToppsTheOneListBackUp() {
		long done = catalogued("done");
		long owed = catalogued("owed");

		fingerprinted(done, 1);
		fingerprinted(owed, 1);

		phashBacklogService.seedRebuild(current());

		drain(current(), chunkOver(List.of(done)));

		assertThat(remaining()).containsExactly(owed);

		long arrived = catalogued("arrived-since");

		phashBacklogService.seedRebuild(current());

		assertThat(remaining()).as("everything is owed again, once each, newcomer included")
				.containsExactlyInAnyOrder(done, owed, arrived);
		assertThat(taskRepository.count()).isEqualTo(3);

		assertThat(mediaFingerprintRepository.count()).as("and asking again deleted no published answer")
				.isEqualTo(2);
	}

	/**
	 * What a restart does. The launcher refuses to queue an empty backlog, so an
	 * open list is what makes it ask - and what it asks for is a drain, never a
	 * rebuild, so nothing is seeded again.
	 */
	@Test
	void aRestartAsksForADrainBecauseTheListStillOwesSomething() {
		long owed = catalogued("owed");

		fingerprinted(owed, 1);

		phashBacklogService.seedRebuild(current());

		assertThat(phashBacklogService.status().pending()).isPositive();

		Execution queued = launcher.launch(ExecutionType.FINGERPRINT_PHOTO, false).orElseThrow();

		assertThat(queued.getDedupKey()).as("a drain, not a rebuild").endsWith(":drain");
		assertThat(taskRepository.count()).as("and nothing was seeded by asking").isEqualTo(1);
		assertThat(mediaFingerprintRepository.count()).isEqualTo(1);
	}

	private void drain(ExecutionOwnership ownership, ReplaceableChunk producer) {
		engine.drain(producer, () -> false, (_, _) -> {
		}, ownership, new ExecutionMetricsContext());
	}

	private ReplaceableChunk chunkOver(List<Long> catalogFileIds) {
		ReplaceableChunk producer = chunk();

		catalogFileIds.forEach(id -> producer.succeedsWith(id, 1));

		return producer;
	}

	private ReplaceableChunk chunk() {
		return new ReplaceableChunk(mediaFingerprintRepository, similarityRelationWriter, taskRepository);
	}

	private ExecutionOwnership current() {
		return Takings.fenced(claimedAt(1), WORKER, 1, executionOwnershipGuard);
	}

	private boolean owes(long catalogFileId) {
		return remaining().contains(catalogFileId);
	}

	private List<Long> remaining() {
		return jdbcTemplate.queryForList(
				"SELECT catalog_file_id FROM fingerprint_rebuild_task WHERE kind = ? AND algorithm = ?"
						+ " ORDER BY catalog_file_id",
				Long.class, PhashBacklogService.KIND.name(), ALGORITHM);
	}

	private LocalDateTime computedAtOf(long catalogFileId) {
		return jdbcTemplate.queryForObject("SELECT min(computed_at) FROM media_fingerprint WHERE catalog_file_id = ?",
				LocalDateTime.class, catalogFileId);
	}

	private int relations() {
		return count("SELECT count(*) FROM similarity_relation WHERE algorithm_id = ?", ALGORITHM);
	}

	private int coverageOf(long catalogFileId) {
		return count("SELECT count(*) FROM similarity_relation_coverage WHERE catalog_file_id = ?", catalogFileId);
	}

	private int count(String sql, Object argument) {
		Integer rows = jdbcTemplate.queryForObject(sql, Integer.class, argument);

		return rows == null ? 0 : rows;
	}

	private void related(long first, long second) {
		similarityRelationWriter.save(RELATIONS, new int[] { 0 }, new int[] { 1 }, new int[] { 96 }, 1,
				new long[] { first, second }, new long[] { first, second });
	}

	private LocalDateTime theOldInstant() {
		return LocalDateTime.of(2020, Month.JANUARY, 1, 12, 0);
	}

	private void wentMissing(long catalogFileId) {
		CatalogFile file = catalogFileRepository.findById(catalogFileId).orElseThrow();

		file.setLifecycleStatus(LifecycleStatus.MISSING);

		catalogFileRepository.saveAndFlush(file);
	}

	private long claimedAt(int claimCount) {
		return executionRepository.saveAndFlush(Execution.builder().executionType(ExecutionType.FINGERPRINT_PHOTO)
				.status(ExecutionStatus.RUNNING).recursive(false).executeFlag(true).claimedBy(WORKER)
				.claimCount(claimCount).leaseUntil(LocalDateTime.now().plusMinutes(10)).build()).getId();
	}

	private void takenAgainAt(long executionId, int claimCount) {
		Execution row = executionRepository.findById(executionId).orElseThrow();

		row.setClaimCount(claimCount);
		row.setLeaseUntil(LocalDateTime.now().plusMinutes(10));

		executionRepository.saveAndFlush(row);

		Takings.fenced(executionId, WORKER, claimCount, executionOwnershipGuard);
	}

	private long catalogued(String name) {
		String key = name + "-" + System.nanoTime();

		String path = "C:/test/" + key + ".jpg";

		CatalogFile file = CatalogFile.builder().extension("jpg").sizeBytes(1L)
				.modifiedAt(Instant.now()).fileType(FileType.PHOTO).lifecycleStatus(LifecycleStatus.ACTIVE)
				.build();
		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(path).currentFolder("C:/test")
				.pathFlavor(PathFlavor.WINDOWS).build());

		return CatalogFiles.catalogued(new TransactionTemplate(transactionManager),
				catalogFileRepository, catalogFileLocationRepository, file).getId();
	}

	private void fingerprinted(long catalogFileId, int samples) {
		for (int sampleIndex = 0; sampleIndex < samples; sampleIndex++) {
			mediaFingerprintRepository.saveAndFlush(MediaFingerprint.builder().catalogFileId(catalogFileId)
					.kind(PhashBacklogService.KIND).algorithm(ALGORITHM).sampleIndex(sampleIndex)
					.hashBytes(new byte[32]).sampleBytes(new byte[1024]).computedAt(theOldInstant()).build());
		}
	}

	private void failed(long catalogFileId, int attempts) {
		fingerprintFailureRepository.saveAndFlush(FingerprintFailure.builder().catalogFileId(catalogFileId)
				.kind(PhashBacklogService.KIND).algorithm(ALGORITHM).attempts(attempts)
				.reason(FingerprintFailureReason.UNKNOWN).lastError("boom").lastAttemptAt(theOldInstant()).build());
	}
}