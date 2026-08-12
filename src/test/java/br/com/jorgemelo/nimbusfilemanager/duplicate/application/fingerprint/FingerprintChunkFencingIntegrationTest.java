package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DrainResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintFailureReason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.FingerprintFailure;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintFailureRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
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
 * Work that was finished by a taking which no longer holds the row.
 *
 * <p>
 * This is the window the chunk boundary exists for, and the only one that costs
 * real time: hashing a chunk is minutes of ffmpeg, and a lease can lapse inside
 * them. The run comes back holding perfectly good fingerprints for a row that is
 * somebody else's by then, and what must not happen is those results landing on
 * top of the taking that replaced it - it has been recomputing the very files
 * this one is about to write.
 *
 * <p>
 * The row changes hands <em>during the compute</em>, which is where it happens
 * in production and is exact rather than timed: the producer hands it over when
 * the last item finishes hashing, so every outcome is decided and nothing is
 * left for the write transaction to find but a taking that is over. Nothing is
 * slept on, and nothing was moved inside the transaction to make it easier to
 * watch - the coordinator still runs the hashing outside it, as it always did.
 *
 * <p>
 * Its own container and no test transaction: each chunk commits in a
 * {@code REQUIRES_NEW} of its own, which is exactly what
 * {@code SharedPostgresIntegrationTest} says it cannot host.
 */
@SpringBootTest
@Testcontainers
class FingerprintChunkFencingIntegrationTest {

	private static final String WORKER = "worker-that-came-back";

	private static final String ALGORITHM = DuplicateConstants.ALGORITHM;

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

	@Autowired
	private MediaFingerprintRepository mediaFingerprintRepository;

	@Autowired
	private FingerprintFailureRepository fingerprintFailureRepository;

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
	private FingerprintBacklogEngine engine;

	@AfterEach
	void forgetEverything() {
		mediaFingerprintRepository.deleteAll();
		fingerprintFailureRepository.deleteAll();
		executionRepository.deleteAll();
		catalogFileRepository.deleteAll();
	}

	/**
	 * A whole chunk hashed by a taking that was replaced while it hashed, with both
	 * branches of the persistence in it: a file that hashed and a file that threw.
	 * Neither may leave a trace.
	 *
	 * <p>
	 * The file that hashed carries the sharper case. It has an older failure row,
	 * which a successful store retires - so a fence that covered only the insert
	 * would still let this run erase a failure that the taking which replaced it is
	 * entitled to see.
	 */
	@Test
	void aChunkHashedByATakingThatWasReplacedIsNotMaterialisedAtAll() {
		long hashes = catalogued("hashes");
		long fails = catalogued("fails");

		long executionId = claimedAt(1);

		ExecutionOwnership replaced = Takings.fenced(executionId, WORKER, 1, executionOwnershipGuard);

		alreadyFailedOnce(hashes);

		DrainResult result = drain(replaced, chunkOf(hashes, fails, () -> takenAgainAt(executionId, 2)));

		assertThat(mediaFingerprintRepository.count()).as("nothing it hashed was inserted").isZero();
		assertThat(fingerprintFailureRepository.count()).as("and nothing it failed was recorded").isEqualTo(1);

		FingerprintFailure untouched = fingerprintFailureRepository.findAll().getFirst();

		assertThat(untouched.getCatalogFileId()).as("the one failure left is the one that was already there")
				.isEqualTo(hashes);
		assertThat(untouched.getAttempts()).as("not retired by the success, not bumped by the failure").isEqualTo(1);

		assertThat(result.processed()).as("and the drain claims nothing it did not commit").isZero();
		assertThat(result.failed()).isZero();
	}

	/**
	 * Same chunk, same two branches, under the taking that holds the row: all of it
	 * lands. Without this the refusal above would prove only that the work never
	 * ran.
	 */
	@Test
	void theTakingThatHoldsTheRowPersistsTheWholeChunk() {
		long hashes = catalogued("hashes");
		long fails = catalogued("fails");

		long executionId = claimedAt(1);

		ExecutionOwnership current = Takings.fenced(executionId, WORKER, 1, executionOwnershipGuard);

		alreadyFailedOnce(hashes);

		DrainResult result = drain(current, chunkOf(hashes, fails, () -> {
		}));

		assertThat(result.processed()).isEqualTo(1);
		assertThat(result.failed()).isEqualTo(1);

		assertThat(mediaFingerprintRepository.findAll()).as("the file that hashed was stored").singleElement()
				.extracting(MediaFingerprint::getCatalogFileId).isEqualTo(hashes);
		assertThat(fingerprintFailureRepository.findAll()).as("its old failure retired, the new one recorded")
				.singleElement().extracting(FingerprintFailure::getCatalogFileId).isEqualTo(fails);
	}

	private DrainResult drain(ExecutionOwnership ownership, ChunkOfPending producer) {
		return engine.drain(producer, () -> false, (_, _) -> {
		}, ownership, new ExecutionMetricsContext());
	}

	private ChunkOfPending chunkOf(long hashes, long fails, Runnable afterTheLastCompute) {
		return new ChunkOfPending(List.of(hashes, fails), fails, mediaFingerprintRepository, afterTheLastCompute);
	}

	/** A row claimed and running, the way the dispatcher leaves one. */
	private long claimedAt(int claimCount) {
		return executionRepository.saveAndFlush(Execution.builder().executionType(ExecutionType.FINGERPRINT_PHOTO)
				.status(ExecutionStatus.RUNNING).recursive(false).executeFlag(true).claimedBy(WORKER)
				.claimCount(claimCount).leaseUntil(LocalDateTime.now().plusMinutes(10)).build()).getId();
	}

	/** Recovery put the row back and the same worker took it again. */
	private void takenAgainAt(long executionId, int claimCount) {
		Execution row = executionRepository.findById(executionId).orElseThrow();

		row.setClaimCount(claimCount);
		row.setLeaseUntil(LocalDateTime.now().plusMinutes(10));

		executionRepository.saveAndFlush(row);

		Takings.fenced(executionId, WORKER, claimCount, executionOwnershipGuard);
	}

	private long catalogued(String name) {
		String path = "C:/test/" + name + "-" + System.nanoTime() + ".jpg";

		CatalogFile file = CatalogFile.builder().extension("jpg").sizeBytes(1L)
				.modifiedAt(Instant.now()).fileType(FileType.PHOTO).lifecycleStatus(LifecycleStatus.ACTIVE)
				.build();
		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(path).currentFolder("C:/test")
				.pathFlavor(PathFlavor.WINDOWS).build());

		return CatalogFiles.catalogued(new TransactionTemplate(transactionManager),
				catalogFileRepository, catalogFileLocationRepository, file).getId();
	}

	private void alreadyFailedOnce(long catalogFileId) {
		fingerprintFailureRepository.saveAndFlush(FingerprintFailure.builder().catalogFileId(catalogFileId)
				.kind(PhashBacklogService.KIND).algorithm(ALGORITHM).attempts(1)
				.reason(FingerprintFailureReason.UNKNOWN).lastError("an earlier attempt")
				.lastAttemptAt(LocalDateTime.now()).build());
	}
}