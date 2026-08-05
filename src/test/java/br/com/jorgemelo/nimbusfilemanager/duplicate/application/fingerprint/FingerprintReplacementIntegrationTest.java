package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DrainResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.RelationParameters;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintFailureReason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.FingerprintFailure;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.FingerprintRebuildTask;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintFailureRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintRebuildTaskRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence.SimilarityRelationWriter;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnershipGuard;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * One file changing hands, and what has to change with it.
 *
 * <p>
 * A fingerprint is not replaced on its own. What was concluded from the hash
 * that is going away - the relations the file took part in and the coverage that
 * says it was compared - stops being checkable at the same instant, and nothing
 * published can tell those apart from conclusions about the hash that replaced
 * it: the digest a grouping carries is over the files it examined, never over
 * their fingerprints. So the replacement, the invalidation and the debt being
 * settled are one transaction or they are a lie.
 *
 * <p>
 * Its own container and no test transaction: every chunk commits in a
 * {@code REQUIRES_NEW} of its own, and what is asserted is what survived the
 * commit. The hashing is decided rather than performed - that is the only thing
 * these tests fake - and every write goes through the production collaborator.
 */
@SpringBootTest
@Testcontainers
class FingerprintReplacementIntegrationTest {

	private static final String WORKER = "worker-that-came-back";

	private static final String ALGORITHM = DuplicateConstants.ALGORITHM;

	private static final RelationParameters RELATIONS = new RelationParameters(ALGORITHM, 8, 90);

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	@Autowired
	private FingerprintBacklogEngine engine;

	@Autowired
	private MediaFingerprintRepository mediaFingerprintRepository;

	@Autowired
	private FingerprintFailureRepository fingerprintFailureRepository;

	@Autowired
	private FingerprintRebuildTaskRepository taskRepository;

	@Autowired
	private SimilarityRelationWriter similarityRelationWriter;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

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

	/** Everything a successful replacement is responsible for, in one commit. */
	@Test
	void aFileThatHashesAgainReplacesEverythingThatWasAboutItsOldHash() {
		long replaced = catalogued("replaced");
		long neighbour = catalogued("neighbour");

		fingerprinted(replaced, 1);
		fingerprinted(neighbour, 1);
		failed(replaced, 1);
		related(replaced, neighbour);
		owed(replaced);

		DrainResult result = drain(current(), chunk().succeedsWith(replaced, 1));

		assertThat(result.processed()).isEqualTo(1);

		assertThat(computedAtOf(replaced)).as("the row it holds now is not the row it held before")
				.isNotEqualTo(theOldInstant());
		assertThat(fingerprintsOf(replaced)).as("and there is exactly one of it").isEqualTo(1);
		assertThat(fingerprintFailureRepository.count()).as("the failure it carried is gone").isZero();
		assertThat(relations()).as("the relations drawn from the hash that went are gone").isZero();
		assertThat(coverageOf(replaced)).as("and its claim of having been compared with it").isZero();
		assertThat(taskRepository.count()).as("the debt is settled").isZero();

		assertThat(fingerprintsOf(neighbour)).as("nothing was done to the file that was not recomputed")
				.isEqualTo(1);
	}

	/** A failure with attempts to spare changes nothing but the failure. */
	@Test
	void aFailureThatMayBeRetriedLeavesTheOldAnswerAndTheDebtWhereTheyAre() {
		long file = catalogued("retryable");
		long neighbour = catalogued("neighbour");

		fingerprinted(file, 1);
		related(file, neighbour);
		owed(file);

		drain(current(), chunk().failsRetryably(file));

		FingerprintFailure failure = fingerprintFailureRepository.findAll().getFirst();

		assertThat(failure.getAttempts()).isEqualTo(1);
		assertThat(failure.getAttempts()).isLessThan(PhashBacklogService.MAX_ATTEMPTS);

		assertThat(fingerprintsOf(file)).as("the old answer is still the answer").isEqualTo(1);
		assertThat(relations()).as("and nothing derived from it was touched").isEqualTo(1);
		assertThat(coverageOf(file)).isEqualTo(1);
		assertThat(taskRepository.count()).as("still owed, because it will be tried again").isEqualTo(1);
	}

	/**
	 * A file that cannot be decoded is not a file whose fingerprint stopped being
	 * true. The debt is settled - no later attempt would answer differently - and
	 * everything it had stays.
	 */
	@Test
	void aFailureNoRetryWouldAnswerSettlesTheDebtAndKeepsTheOldAnswer() {
		long file = catalogued("corrupt");
		long neighbour = catalogued("neighbour");

		fingerprinted(file, 1);
		related(file, neighbour);
		owed(file);

		drain(current(), chunk().failsTerminally(file));

		assertThat(fingerprintFailureRepository.findAll().getFirst().getAttempts())
				.isEqualTo(PhashBacklogService.MAX_ATTEMPTS);
		assertThat(taskRepository.count()).as("nothing more is owed for it").isZero();

		assertThat(fingerprintsOf(file)).as("what it had is what it keeps").isEqualTo(1);
		assertThat(relations()).isEqualTo(1);
		assertThat(coverageOf(file)).isEqualTo(1);
	}

	/**
	 * And the budget runs out the ordinary way too, one retry at a time - not only
	 * through a reason that spends it all at once.
	 */
	@Test
	void theLastRetryableAttemptSettlesTheDebtAsWell() {
		long file = catalogued("nearly-spent");

		fingerprinted(file, 1);
		failed(file, PhashBacklogService.MAX_ATTEMPTS - 1);
		owed(file);

		drain(current(), chunk().failsRetryably(file));

		assertThat(fingerprintFailureRepository.findAll().getFirst().getAttempts())
				.isEqualTo(PhashBacklogService.MAX_ATTEMPTS);
		assertThat(taskRepository.count()).as("the budget ran out, so the debt is settled").isZero();
		assertThat(fingerprintsOf(file)).isEqualTo(1);
	}

	/**
	 * The same worker, one attempt later. Nothing the replaced taking computed may
	 * land: not the fingerprint, not the failure, not the invalidation, not the
	 * debt.
	 */
	@Test
	void aTakingThatWasReplacedChangesNothingAtAll() {
		long file = catalogued("contested");
		long neighbour = catalogued("neighbour");

		long executionId = claimedAt(1);

		ExecutionOwnership replaced = Takings.fenced(executionId, WORKER, 1, executionOwnershipGuard);

		fingerprinted(file, 1);
		failed(file, 1);
		related(file, neighbour);
		owed(file);

		takenAgainAt(executionId, 2);

		DrainResult result = drain(replaced, chunk().succeedsWith(file, 1));

		assertThat(result.processed()).isZero();
		assertThat(computedAtOf(file)).as("the fingerprint it holds is the one it had").isEqualTo(theOldInstant());
		assertThat(fingerprintsOf(file)).isEqualTo(1);
		assertThat(fingerprintFailureRepository.findAll().getFirst().getAttempts())
				.as("the failure was neither removed nor bumped").isEqualTo(1);
		assertThat(relations()).as("nothing derived was invalidated").isEqualTo(1);
		assertThat(coverageOf(file)).isEqualTo(1);
		assertThat(taskRepository.count()).as("and the debt is still owed").isEqualTo(1);

		ExecutionOwnership current = Takings.fenced(executionId, WORKER, 2, executionOwnershipGuard);

		assertThat(drain(current, chunk().succeedsWith(file, 1)).processed())
				.as("while the taking that holds the row works normally").isEqualTo(1);
		assertThat(taskRepository.count()).isZero();
	}

	/**
	 * A failure after the fingerprint was replaced takes the replacement with it.
	 * What must not exist afterwards is a file holding a new hash whose relations
	 * were never reconsidered.
	 */
	@Test
	void aFailureAfterTheReplacementPutsEverythingBack() {
		long file = catalogued("half-written");
		long neighbour = catalogued("neighbour");

		fingerprinted(file, 1);
		failed(file, 1);
		related(file, neighbour);
		owed(file);

		ReplaceableChunk sabotaged = chunk().succeedsWith(file, 1)
				.brokenAfterTheFingerprintIsReplaced(() -> {
					throw new IllegalStateException("the write went wrong");
				});

		ExecutionOwnership taking = current();

		assertThatThrownBy(() -> drain(taking, sabotaged)).isInstanceOf(IllegalStateException.class);

		assertThat(computedAtOf(file)).as("the old fingerprint is back, whole").isEqualTo(theOldInstant());
		assertThat(fingerprintsOf(file)).isEqualTo(1);
		assertThat(fingerprintFailureRepository.findAll().getFirst().getAttempts()).isEqualTo(1);
		assertThat(relations()).isEqualTo(1);
		assertThat(coverageOf(file)).isEqualTo(1);
		assertThat(taskRepository.count()).as("and nothing was settled").isEqualTo(1);
	}

	/**
	 * The proof the new dependency exists for: a failure while the derived state
	 * is being forgotten rolls the fingerprint back with it. If these were two
	 * transactions this is where they would come apart.
	 */
	@Test
	void aFailureWhileForgettingWhatWasDerivedPutsTheFingerprintBackToo() {
		long file = catalogued("half-forgotten");
		long neighbour = catalogued("neighbour");

		fingerprinted(file, 1);
		related(file, neighbour);
		owed(file);

		ReplaceableChunk sabotaged = chunk().succeedsWith(file, 1)
				.brokenWhileForgettingWhatWasDerived(() -> {
					throw new IllegalStateException("forgetting went wrong");
				});

		ExecutionOwnership taking = current();

		assertThatThrownBy(() -> drain(taking, sabotaged)).isInstanceOf(IllegalStateException.class);

		assertThat(computedAtOf(file)).as("the fingerprint went back with the invalidation")
				.isEqualTo(theOldInstant());
		assertThat(relations()).as("and the relations were never forgotten").isEqualTo(1);
		assertThat(coverageOf(file)).isEqualTo(1);
		assertThat(taskRepository.count()).isEqualTo(1);
	}

	/**
	 * Only what was recomputed loses what was said about it. A wholesale forget
	 * would take the untouched file's conclusions with it, which is exactly the
	 * behaviour this slice replaced.
	 */
	@Test
	void onlyTheRecomputedFileLosesWhatWasConcludedAboutIt() {
		long recomputed = catalogued("recomputed");
		long untouched = catalogued("untouched");
		long third = catalogued("third");

		fingerprinted(recomputed, 1);
		fingerprinted(untouched, 1);
		fingerprinted(third, 1);
		related(recomputed, third);
		related(untouched, third);
		owed(recomputed);

		drain(current(), chunk().succeedsWith(recomputed, 1));

		assertThat(coverageOf(recomputed)).as("the recomputed file must be compared again").isZero();
		assertThat(coverageOf(untouched)).as("the untouched one has nothing to reconsider").isEqualTo(1);
		assertThat(relations()).as("only the pair the recomputed file was in went").isEqualTo(1);
		assertThat(fingerprintsOf(untouched)).isEqualTo(1);
	}

	/**
	 * A video re-read at a different duration is sampled at a different number of
	 * frames. Replacing sample by sample would leave the tail of the old set
	 * behind, attributed to a hash that never produced it.
	 */
	@Test
	void aSetOfSamplesIsReplacedWhole() {
		long shrinks = catalogued("five-to-three");
		long grows = catalogued("two-to-four");

		fingerprinted(shrinks, 5);
		fingerprinted(grows, 2);
		owed(shrinks);
		owed(grows);

		drain(current(), chunk().succeedsWith(shrinks, 3).succeedsWith(grows, 4));

		assertThat(fingerprintsOf(shrinks)).as("three, and no tail of the five").isEqualTo(3);
		assertThat(sampleIndexesOf(shrinks)).containsExactly(0, 1, 2);
		assertThat(fingerprintsOf(grows)).isEqualTo(4);
		assertThat(sampleIndexesOf(grows)).containsExactly(0, 1, 2, 3);
	}

	/**
	 * A file the catalog lost sight of is a debt that cannot be paid. Settling it
	 * is all that happens - it says nothing about the fingerprint the file still
	 * has, or about anything concluded from it.
	 */
	@Test
	void aDebtForAFileThatIsNoLongerACandidateIsDroppedAndNothingElseIs() {
		long missing = catalogued("went-missing");
		long neighbour = catalogued("neighbour");

		fingerprinted(missing, 1);
		failed(missing, 1);
		related(missing, neighbour);
		owed(missing);

		wentMissing(missing);

		assertThat(engine.discardIneligible(chunk(), current())).isEqualTo(1);

		assertThat(taskRepository.count()).as("the debt is dropped").isZero();
		assertThat(fingerprintsOf(missing)).as("the fingerprint is not").isEqualTo(1);
		assertThat(fingerprintFailureRepository.findAll().getFirst().getAttempts())
				.as("nor is the failure touched").isEqualTo(1);
		assertThat(relations()).as("nor anything derived").isEqualTo(1);
		assertThat(coverageOf(missing)).isEqualTo(1);
	}

	/** And that delete is fenced like every other mutation the worker makes. */
	@Test
	void aTakingThatWasReplacedCannotDropTheDebtEither() {
		long missing = catalogued("went-missing");

		long executionId = claimedAt(1);

		ExecutionOwnership replaced = Takings.fenced(executionId, WORKER, 1, executionOwnershipGuard);

		owed(missing);
		wentMissing(missing);

		takenAgainAt(executionId, 2);

		assertThat(engine.discardIneligible(chunk(), replaced)).isZero();
		assertThat(taskRepository.count()).as("still owed, because the run that asked is over").isEqualTo(1);
	}

	/**
	 * Dropping the debt is not a decision that the file needs no fingerprint. If
	 * it comes back, the ordinary queue asks the ordinary question again - and the
	 * work list is not a standing watch over it.
	 */
	@Test
	void aFileThatComesBackIsTheOrdinaryQueuesBusinessAgain() {
		long file = catalogued("came-back");

		owed(file);
		wentMissing(file);

		engine.discardIneligible(chunk(), current());

		assertThat(taskRepository.count()).isZero();

		CatalogFile back = catalogFileRepository.findById(file).orElseThrow();

		back.setLifecycleStatus(LifecycleStatus.ACTIVE);

		catalogFileRepository.saveAndFlush(back);

		assertThat(taskRepository.count()).as("no debt is written back").isZero();
		assertThat(mediaFingerprintRepository.countPendingPhotos(PhashBacklogService.KIND, ALGORITHM,
				PhashBacklogService.MAX_ATTEMPTS)).as("and the ordinary rule finds it, because it has no hash")
				.isEqualTo(1);
	}

	private DrainResult drain(ExecutionOwnership ownership, ReplaceableChunk producer) {
		return engine.drain(producer, () -> false, (_, _) -> {
		}, ownership);
	}

	private ReplaceableChunk chunk() {
		return new ReplaceableChunk(mediaFingerprintRepository, similarityRelationWriter, taskRepository);
	}

	private ExecutionOwnership current() {
		return Takings.fenced(claimedAt(1), WORKER, 1, executionOwnershipGuard);
	}

	private LocalDateTime theOldInstant() {
		return LocalDateTime.of(2020, Month.JANUARY, 1, 12, 0);
	}

	private int fingerprintsOf(long catalogFileId) {
		return count("SELECT count(*) FROM media_fingerprint WHERE catalog_file_id = ?", catalogFileId);
	}

	private List<Integer> sampleIndexesOf(long catalogFileId) {
		return jdbcTemplate.queryForList(
				"SELECT sample_index FROM media_fingerprint WHERE catalog_file_id = ? ORDER BY sample_index",
				Integer.class, catalogFileId);
	}

	private LocalDateTime computedAtOf(long catalogFileId) {
		return jdbcTemplate.queryForObject(
				"SELECT min(computed_at) FROM media_fingerprint WHERE catalog_file_id = ?", LocalDateTime.class,
				catalogFileId);
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

	private void owed(long catalogFileId) {
		taskRepository.saveAndFlush(FingerprintRebuildTask.builder().kind(PhashBacklogService.KIND)
				.algorithm(ALGORITHM).catalogFileId(catalogFileId).seededAt(LocalDateTime.now()).build());
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

		CatalogFile file = CatalogFile.builder().fileKey(key).fileName(key + ".jpg").extension("jpg").sizeBytes(1L)
				.modifiedAt(LocalDateTime.now()).fileType(FileType.PHOTO).lifecycleStatus(LifecycleStatus.ACTIVE)
				.build();
		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(path).currentFolder("C:/test")
				.originalPath(path).originalFolder("C:/test").build());

		return catalogFileRepository.saveAndFlush(file).getId();
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