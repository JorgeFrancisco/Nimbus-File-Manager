package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.OptionalLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintFailureReason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.FingerprintFailure;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintFailureRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintRebuildTaskRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
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
 * Opening a rebuild without emptying the library.
 *
 * <p>
 * A rebuild used to begin by deleting every fingerprint of its kind, because
 * "still to do" meant "has no row" and there was no other way to make the work
 * exist again. A run interrupted after that delete left the whole algorithm
 * missing - for as long as recomputing it took - and every consumer went on
 * reading the remains as the truth. What begins a rebuild now is writing down
 * what it owes, and the fingerprints stay published until each is replaced by
 * its own.
 *
 * <p>
 * Against a real database and without a test transaction, because the seed
 * commits in a {@code REQUIRES_NEW} of its own. Every assertion reads rows back.
 */
@SpringBootTest
@Testcontainers
class FingerprintRebuildSeedFencingIntegrationTest {

	private static final String WORKER = "worker-that-came-back";

	private static final String ALGORITHM = DuplicateConstants.ALGORITHM;

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	@Autowired
	private PhashBacklogService phashBacklogService;

	@Autowired
	private FingerprintRebuildTaskRepository taskRepository;

	@Autowired
	private MediaFingerprintRepository mediaFingerprintRepository;

	@Autowired
	private FingerprintFailureRepository fingerprintFailureRepository;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private ExecutionOwnershipGuard executionOwnershipGuard;

	@AfterEach
	void forgetEverything() {
		taskRepository.deleteAll();
		mediaFingerprintRepository.deleteAll();
		fingerprintFailureRepository.deleteAll();
		executionRepository.deleteAll();
		catalogFileRepository.deleteAll();
	}

	/**
	 * The whole point of the slice, in one assertion pair: after opening a
	 * rebuild, the library still answers exactly as it did.
	 */
	@Test
	void seedingOwesEveryEligiblePhotoAndDiscardsNothing() {
		long fingerprinted = catalogued("has-a-hash", FileType.PHOTO);
		long bare = catalogued("has-none", FileType.PHOTO);

		fingerprinted(fingerprinted);

		OptionalLong owed = phashBacklogService.seedRebuild(current());

		assertThat(owed).as("both photos are owed").hasValue(2);
		assertThat(taskRepository.countByKindAndAlgorithm(PhashBacklogService.KIND, ALGORITHM)).isEqualTo(2);

		assertThat(mediaFingerprintRepository.findAll()).as("the published fingerprint is untouched").singleElement()
				.extracting(MediaFingerprint::getCatalogFileId).isEqualTo(fingerprinted);
		assertThat(taskRepository.count()).as("and the photo that never had one is owed too").isEqualTo(2);

		assertThat(bare).isPositive();
	}

	/**
	 * Videos are a different target and are not swept up by a photo rebuild - the
	 * work list is keyed by the pair, so one kind's rebuild owes nothing of the
	 * other's.
	 */
	@Test
	void aPhotoRebuildOwesNoVideos() {
		catalogued("a-photo", FileType.PHOTO);
		catalogued("a-video", FileType.VIDEO);

		phashBacklogService.seedRebuild(current());

		assertThat(taskRepository.count()).isEqualTo(1);
	}

	/**
	 * The diagnosis survives; only the budget comes back.
	 *
	 * <p>
	 * A file that failed keeps the reason, the message the tool gave and when it
	 * was last tried - that is what the screen shows about a file it could not
	 * read, and none of it stopped being true because a recompute was asked for.
	 * What has to change is that the file may be attempted again.
	 */
	@Test
	void seedingGivesTheAttemptsBackWithoutForgettingWhyTheyWereSpent() {
		long exhausted = catalogued("cannot-decode", FileType.PHOTO);

		LocalDateTime lastAttempt = LocalDateTime.now().minusDays(3).withNano(0);

		failed(exhausted, 3, lastAttempt);

		phashBacklogService.seedRebuild(current());

		FingerprintFailure after = fingerprintFailureRepository.findAll().getFirst();

		assertThat(after.getAttempts()).as("the budget is back").isZero();
		assertThat(after.getReason()).as("and the diagnosis is not").isEqualTo(FingerprintFailureReason.UNKNOWN);
		assertThat(after.getLastError()).isEqualTo("ffmpeg said no");
		assertThat(after.getLastAttemptAt()).isEqualTo(lastAttempt);

		assertThat(taskRepository.countByKindAndAlgorithm(PhashBacklogService.KIND, ALGORITHM))
				.as("a file that had spent its attempts is owed again by a full rebuild").isEqualTo(1);
	}

	/** Asking twice tops the list back up; it does not build a second one. */
	@Test
	void askingAgainAddsWhatIsMissingAndDuplicatesNothing() {
		catalogued("first", FileType.PHOTO);

		assertThat(phashBacklogService.seedRebuild(current())).hasValue(1);

		catalogued("arrived-since", FileType.PHOTO);

		assertThat(phashBacklogService.seedRebuild(current())).as("only the newcomer is added").hasValue(1);
		assertThat(taskRepository.countByKindAndAlgorithm(PhashBacklogService.KIND, ALGORITHM)).isEqualTo(2);
	}

	/**
	 * A resume is not a reseed. The work list is what the second attempt adopts,
	 * and a file the first attempt had already finished stays finished - proved
	 * here by consuming its task and asking again for what is owed.
	 */
	@Test
	void whatIsOwedShrinksAsWorkIsDoneAndSeedingIsNotWhatResumesIt() {
		long done = catalogued("done", FileType.PHOTO);

		catalogued("still-owed", FileType.PHOTO);

		phashBacklogService.seedRebuild(current());

		assertThat(taskRepository.consume(PhashBacklogService.KIND, ALGORITHM, done)).isEqualTo(1);

		assertThat(taskRepository.countByKindAndAlgorithm(PhashBacklogService.KIND, ALGORITHM))
				.as("what is left is what is left").isEqualTo(1);
		assertThat(phashBacklogService.status().pending()).as("and that is what the backlog reports as pending")
				.isEqualTo(1);
	}

	/**
	 * A taking that was replaced writes nothing down and gives nothing back. Both
	 * halves are inside one pinned transaction, so a refused pin leaves neither a
	 * task nor a restored budget.
	 */
	@Test
	void aSeedFromATakingThatWasReplacedWritesNothingDown() {
		long exhausted = catalogued("cannot-decode", FileType.PHOTO);

		failed(exhausted, 3, LocalDateTime.now().withNano(0));

		long executionId = claimedAt(1);

		ExecutionOwnership replaced = Takings.fenced(executionId, WORKER, 1, executionOwnershipGuard);

		takenAgainAt(executionId, 2);

		assertThat(phashBacklogService.seedRebuild(replaced)).as("the seed says it did not run").isEmpty();

		assertThat(taskRepository.count()).as("nothing is owed").isZero();
		assertThat(fingerprintFailureRepository.findAll().getFirst().getAttempts())
				.as("and the budget was not restored either").isEqualTo(3);
	}

	/**
	 * Without a rebuild open, pending is what it always was: the files that have
	 * no fingerprint and have not spent their attempts. The two rules are never
	 * added together - exactly one of them is asked.
	 */
	@Test
	void withoutAnOpenRebuildPendingIsStillTheAbsenceOfAFingerprint() {
		long fingerprinted = catalogued("has-a-hash", FileType.PHOTO);

		catalogued("has-none", FileType.PHOTO);

		fingerprinted(fingerprinted);

		assertThat(phashBacklogService.status().pending()).as("only the one without a hash").isEqualTo(1);

		phashBacklogService.seedRebuild(current());

		assertThat(phashBacklogService.status().pending()).as("and once a rebuild is open, what it owes").isEqualTo(2);
	}

	private ExecutionOwnership current() {
		long executionId = claimedAt(1);

		return Takings.fenced(executionId, WORKER, 1, executionOwnershipGuard);
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

	private long catalogued(String name, FileType fileType) {
		String key = name + "-" + System.nanoTime();

		String path = "C:/test/" + key + ".jpg";

		CatalogFile file = CatalogFile.builder().fileKey(key).fileName(key + ".jpg").extension("jpg").sizeBytes(1L)
				.modifiedAt(LocalDateTime.now()).fileType(fileType).lifecycleStatus(LifecycleStatus.ACTIVE).build();
		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(path).currentFolder("C:/test")
				.originalPath(path).originalFolder("C:/test").build());

		return catalogFileRepository.saveAndFlush(file).getId();
	}

	private void fingerprinted(long catalogFileId) {
		mediaFingerprintRepository.saveAndFlush(MediaFingerprint.builder().catalogFileId(catalogFileId)
				.kind(PhashBacklogService.KIND).algorithm(ALGORITHM).sampleIndex(0).hashBytes(new byte[32])
				.sampleBytes(new byte[1024]).computedAt(LocalDateTime.now()).build());
	}

	private void failed(long catalogFileId, int attempts, LocalDateTime lastAttemptAt) {
		fingerprintFailureRepository.saveAndFlush(FingerprintFailure.builder().catalogFileId(catalogFileId)
				.kind(PhashBacklogService.KIND).algorithm(ALGORITHM).attempts(attempts)
				.reason(FingerprintFailureReason.UNKNOWN).lastError("ffmpeg said no").lastAttemptAt(lastAttemptAt)
				.build());
	}
}