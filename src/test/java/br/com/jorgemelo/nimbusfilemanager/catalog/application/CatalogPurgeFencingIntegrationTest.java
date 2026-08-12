package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnershipGuard;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;

/**
 * The purge is the one of these four that cannot be undone.
 *
 * <p>
 * Everything else an obsolete taking might materialise can be recomputed:
 * fingerprints hash again, groupings are derived. A purged catalog row is gone,
 * and with it the placement and the retention clock that decided it was overdue
 * - so a run that was replaced while it counted days must not be the one that
 * carries out the sentence. The taking that replaced it may well have seen the
 * files come back.
 *
 * <p>
 * Against a real database, on the shared container: the purge runs in a plain
 * {@code @Transactional} that joins the caller's, so it commits nothing of its
 * own and the test transaction can hold the whole thing. The assertions read
 * rows back rather than counting calls, and the cascades - the placement that
 * follows the file, the movement that only detaches from it - are checked on
 * both sides of the refusal.
 */
class CatalogPurgeFencingIntegrationTest extends SharedPostgresIntegrationTest {

	private static final String WORKER = "worker-that-came-back";

	private static final int DAYS = 90;

	/**
	 * The clock the application writes with, so what this test compares against is
	 * in the same frame as what production stored. {@code LocalDateTime.now()} reads
	 * the JVM's default zone while the row was written in the configured one, and
	 * on any machine where the two differ - every CI runner - a fresh lease looked
	 * hours expired and an expired one looked fresh.
	 */
	@Autowired
	private Clock clock;

	@Autowired
	private CatalogFileRetentionService catalogFileRetentionService;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private MovementRepository movementRepository;

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private ExecutionOwnershipGuard executionOwnershipGuard;

	/**
	 * A purge that arrives after its row was taken again deletes nothing - and
	 * leaves behind everything a purge would have taken with it.
	 */
	@Test
	void aPurgeFromATakingThatWasReplacedDeletesNothing() {
		CatalogFile overdue = missingSince(200);

		Movement audit = movedBySomeEarlierRun(overdue);

		long executionId = claimedAt(1);

		ExecutionOwnership replaced = Takings.fenced(executionId, WORKER, 1, executionOwnershipGuard);

		takenAgainAt(executionId, 2);

		OptionalInt purged = catalogFileRetentionService.purgeMissingOlderThan(DAYS, replaced);

		assertThat(purged).as("the purge says it did not run").isEmpty();
		assertThat(catalogFileRepository.findById(overdue.getId())).as("the overdue row is still catalogued")
				.isPresent();
		assertThat(placementsOf(overdue.getId())).as("with the placement that would cascade").isOne();
		assertThat(movementRepository.findById(audit.getId())).get().extracting(Movement::getCatalogFile)
				.as("and the audit still points at it, undetached").isNotNull();
	}

	/**
	 * The taking that holds the row purges as before, and a second pass over the
	 * same window finds nothing left to do. The repeat matters because the refusal
	 * above leaves the work outstanding: whoever holds the row next has to be able
	 * to finish it, and finishing it twice must not be an error.
	 */
	@Test
	void theTakingThatHoldsTheRowPurgesAndRepeatingItChangesNothing() {
		CatalogFile overdue = missingSince(200);
		CatalogFile recent = missingSince(1);

		Movement audit = movedBySomeEarlierRun(overdue);

		long executionId = claimedAt(1);

		ExecutionOwnership current = Takings.fenced(executionId, WORKER, 1, executionOwnershipGuard);

		assertThat(catalogFileRetentionService.purgeMissingOlderThan(DAYS, current)).hasValue(1);

		assertThat(catalogFileRepository.findById(overdue.getId())).isEmpty();
		assertThat(placementsOf(overdue.getId())).as("placement cascaded away").isZero();
		assertThat(catalogFileRepository.findById(recent.getId())).as("and the recent one is not overdue yet")
				.isPresent();
		assertThat(movementRepository.findById(audit.getId()))
				.as("the operation was this file's, and the file is gone for good").isEmpty();

		assertThat(catalogFileRetentionService.purgeMissingOlderThan(DAYS, current)).as("nothing left to purge")
				.hasValue(0);
	}

	/**
	 * A lease that ran out reaches the same refusal without anyone having claimed
	 * the row again - which is the shape a worker that was only slow arrives in,
	 * before recovery has even noticed.
	 */
	@Test
	void aPurgeWhoseLeaseRanOutIsRefusedBeforeRecoveryHasEvenRun() {
		CatalogFile overdue = missingSince(200);

		long executionId = claimedAt(1);

		ExecutionOwnership lapsed = Takings.fenced(executionId, WORKER, 1, executionOwnershipGuard);

		Execution row = executionRepository.findById(executionId).orElseThrow();

		row.setLeaseUntil(LocalDateTime.now(clock).minusMinutes(1));

		executionRepository.saveAndFlush(row);

		assertThat(catalogFileRetentionService.purgeMissingOlderThan(DAYS, lapsed)).isEmpty();
		assertThat(catalogFileRepository.findById(overdue.getId())).isPresent();
	}

	/**
	 * A window of zero days answers before the pin, because it is not a purge at
	 * all: retention turned off deletes nothing, and asking the database who owns
	 * the row to establish that would be a question with no consequence.
	 */
	@Test
	void retentionTurnedOffAnswersWithoutAskingWhoHoldsTheRow() {
		CatalogFile overdue = missingSince(200);

		assertThat(catalogFileRetentionService.purgeMissingOlderThan(0, Takings.over(1L))).hasValue(0);
		assertThat(catalogFileRepository.findById(overdue.getId())).isPresent();
	}

	private long claimedAt(int claimCount) {
		return executionRepository.saveAndFlush(Execution.builder().executionType(ExecutionType.CATALOG_PURGE)
				.status(ExecutionStatus.RUNNING).recursive(false).executeFlag(true).claimedBy(WORKER)
				.claimCount(claimCount).leaseUntil(LocalDateTime.now(clock).plusMinutes(10)).build()).getId();
	}

	private void takenAgainAt(long executionId, int claimCount) {
		Execution row = executionRepository.findById(executionId).orElseThrow();

		row.setClaimCount(claimCount);
		row.setLeaseUntil(LocalDateTime.now(clock).plusMinutes(10));

		executionRepository.saveAndFlush(row);

		Takings.fenced(executionId, WORKER, claimCount, executionOwnershipGuard);
	}

	/**
	 * Asked of the file, because a placement has an id of its own: the two
	 * sequences run together in a database built in one pass and drift apart in
	 * any other, and a question keyed on the wrong one answers by coincidence.
	 */
	private int placementsOf(long catalogFileId) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM catalog_file_location WHERE catalog_file_id = ?",
				Integer.class, catalogFileId);
	}

	private CatalogFile missingSince(int days) {
		String key = "catalog-purge-fencing-" + System.nanoTime();

		String path = "C:/test/" + key + ".jpg";

		CatalogFile file = CatalogFile.builder().extension("jpg").sizeBytes(1L)
				.modifiedAt(Instant.now(clock)).fileType(FileType.PHOTO).lifecycleStatus(LifecycleStatus.MISSING)
				.lifecycleChangedAt(Instant.now(clock).minus(days, ChronoUnit.DAYS)).build();
		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(path).currentFolder("C:/test")
				.pathFlavor(PathFlavor.WINDOWS).build());

		return CatalogFiles.catalogued(catalogFileRepository, catalogFileLocationRepository, file);
	}

	private Movement movedBySomeEarlierRun(CatalogFile file) {
		Execution organization = executionRepository.saveAndFlush(Execution.builder()
				.executionType(ExecutionType.ORGANIZATION).status(ExecutionStatus.FINISHED).startedAt(
						LocalDateTime.now(clock))
				.sourcePath("D:/src").targetPath("D:/dst").recursive(true).executeFlag(true).build());

		// A moved operation says when it moved - the database refuses one that does
		// not, which is the only half of the story it can hold on its own.
		return movementRepository.saveAndFlush(Movement.builder().execution(organization).catalogFile(file)
				.requestedSourcePath("D:/src/a").requestedTargetPath("D:/dst/a").status(MovementStatus.MOVED)
				.movedAt(Instant.now(clock)).build());
	}
}