package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Savepoint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.application.LocationChangeException;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.AppliedLocationChange;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.CatalogFactProvenance;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.LocationChange;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationChangeFailure;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * The write door, exercised the way the product uses it.
 *
 * <p>
 * Paths come from {@link TempDir} rather than being spelled out, and that is not
 * tidiness: the writer normalises what it is given, so a literal
 * {@code D:\library} is an absolute path on Windows and a one-segment relative
 * path on the Linux runner, where normalisation would silently prefix the
 * runner's working directory. What flavour those real paths are read under
 * differs between the two machines by design - the flavour-specific rules are
 * proved against both in {@code CatalogSchemaIntegrationTest}, where they can be
 * stated instead of inherited from whoever is running.
 *
 * <p>
 * Everything here happens inside the test transaction, so nothing asserts on
 * {@code updated_at} moving: {@code CURRENT_TIMESTAMP} is the transaction's, and
 * a row seeded and moved in one transaction carries one timestamp. That is the
 * intended reading of "when this placement last changed" and is proved across
 * transactions where it can be - in {@code CatalogLocationRaceIntegrationTest}.
 */
class CatalogLocationWriterIntegrationTest extends SharedPostgresIntegrationTest {

	private static final String SOURCE = "TEST";

	@Autowired
	private CatalogLocationWriter catalogLocationWriter;

	/**
	 * The same transaction the writer writes in.
	 *
	 * <p>
	 * A connection of its own would be a second transaction, and the test class is
	 * {@code @Transactional}: everything the writer does is uncommitted until the
	 * rollback, so a fresh connection reads the row as it was before and the test
	 * reports a door that did nothing. It also leaves nothing behind - a helper
	 * that commits on its own outlives the test and turns up in whatever runs
	 * next against the shared database.
	 */
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@TempDir
	private Path library;

	private Path origin;
	private Path sibling;
	private Path elsewhere;

	@BeforeEach
	void resolveNames() {
		origin = library.resolve("origem.jpg");
		sibling = library.resolve("vizinho.jpg");
		elsewhere = library.resolve("outra").resolve("origem.jpg");
	}

	@Test
	void aRenameMovesThePlacementToTheNewName() {
		long file = seed(origin, "ACTIVE");

		AppliedLocationChange applied = catalogLocationWriter.rename(change(file, origin, sibling));

		Assertions.assertThat(applied.currentPath()).isEqualTo(PathUtils.normalize(sibling));
		Assertions.assertThat(applied.replayed()).isFalse();
		Assertions.assertThat(currentPathOf(file)).isEqualTo(PathUtils.normalize(sibling));
	}

	@Test
	void aRenameIsRecordedAsWhatItWasAndWhatItBecame() {
		long file = seed(origin, "ACTIVE");
		UUID event = UUID.randomUUID();

		catalogLocationWriter.rename(new LocationChange(file, event, origin, sibling, provenance()));

		Assertions.assertThat(eventTypeOf(event)).isEqualTo("RENAMED");
		Assertions.assertThat(oldPathOf(event)).isEqualTo(PathUtils.normalize(origin));
	}

	@Test
	void aMoveTakesTheFileOutOfItsFolder() {
		long file = seed(origin, "ACTIVE");

		AppliedLocationChange applied = catalogLocationWriter.move(change(file, origin, elsewhere));

		Assertions.assertThat(applied.currentPath()).isEqualTo(PathUtils.normalize(elsewhere));
		Assertions.assertThat(applied.currentFolder()).isNotEqualTo(applied.pathKey());
	}

	@Test
	void aMoveIsRecordedAsAMove() {
		long file = seed(origin, "ACTIVE");
		UUID event = UUID.randomUUID();

		catalogLocationWriter.move(new LocationChange(file, event, origin, elsewhere, provenance()));

		Assertions.assertThat(eventTypeOf(event)).isEqualTo("MOVED");
	}

	/** Calling the wrong one would put the wrong kind of fact in the history. */
	@Test
	void renamingSomethingOutOfItsFolderIsRefused() {
		long file = seed(origin, "ACTIVE");

		assertRefused(() -> catalogLocationWriter.rename(change(file, origin, elsewhere)),
				LocationChangeFailure.INVALID_CHANGE);
	}

	@Test
	void movingSomethingThatStaysInItsFolderIsRefused() {
		long file = seed(origin, "ACTIVE");

		assertRefused(() -> catalogLocationWriter.move(change(file, origin, sibling)),
				LocationChangeFailure.INVALID_CHANGE);
	}

	@Test
	void renamingAFileToItsOwnNameIsRefused() {
		long file = seed(origin, "ACTIVE");

		assertRefused(() -> catalogLocationWriter.rename(change(file, origin, origin)),
				LocationChangeFailure.INVALID_CHANGE);
	}

	/** The caller decided from a reading the catalog has since moved past. */
	@Test
	void aCallerThatBelievesTheFileIsSomewhereElseIsRefused() {
		long file = seed(origin, "ACTIVE");

		assertRefused(() -> catalogLocationWriter.rename(change(file, sibling, library.resolve("novo.jpg"))),
				LocationChangeFailure.STALE_LOCATION);
	}

	@Test
	void aFileTheCatalogDoesNotKnowCannotBeMoved() {
		assertRefused(() -> catalogLocationWriter.rename(change(987654321L, origin, sibling)),
				LocationChangeFailure.CATALOG_FILE_NOT_FOUND);
	}

	@Test
	void aKnownFileWithNoPlacementCannotBeMoved() {
		long file = seedWithoutLocation();

		assertRefused(() -> catalogLocationWriter.rename(change(file, origin, sibling)),
				LocationChangeFailure.LOCATION_NOT_FOUND);
	}

	@Test
	void aDestinationAnotherPresentFileOccupiesIsRefused() {
		seed(sibling, "ACTIVE");
		long moving = seed(origin, "ACTIVE");

		assertRefused(() -> catalogLocationWriter.rename(change(moving, origin, sibling)),
				LocationChangeFailure.PATH_OCCUPIED);
	}

	/**
	 * A file that went missing keeps the path as the last thing known about it. It
	 * is a memory, not an occupant, and refusing to write over it is how the
	 * catalog used to end up unable to record a file that really was there.
	 */
	@Test
	void aFileMissingFromTheDestinationDoesNotBlockIt() {
		seed(sibling, "MISSING");
		long moving = seed(origin, "ACTIVE");

		AppliedLocationChange applied = catalogLocationWriter.rename(change(moving, origin, sibling));

		Assertions.assertThat(applied.currentPath()).isEqualTo(PathUtils.normalize(sibling));
	}

	@Test
	void aFileRemovedFromTheDestinationDoesNotBlockIt() {
		seed(sibling, "DELETED");
		long moving = seed(origin, "ACTIVE");

		AppliedLocationChange applied = catalogLocationWriter.rename(change(moving, origin, sibling));

		Assertions.assertThat(applied.currentPath()).isEqualTo(PathUtils.normalize(sibling));
	}

	@Test
	void aRefusedChangeLeavesNeitherTheFactNorThePlacement() {
		seed(sibling, "ACTIVE");
		long moving = seed(origin, "ACTIVE");
		UUID event = UUID.randomUUID();

		assertRefused(
				() -> catalogLocationWriter
						.rename(new LocationChange(moving, event, origin, sibling, provenance())),
				LocationChangeFailure.PATH_OCCUPIED);

		Assertions.assertThat(eventCountOf(event)).isZero();
		Assertions.assertThat(currentPathOf(moving)).isEqualTo(PathUtils.normalize(origin));
	}

	@Test
	void theSameChangeMadeTwiceIsRecordedOnce() {
		long file = seed(origin, "ACTIVE");
		UUID event = UUID.randomUUID();

		catalogLocationWriter.rename(new LocationChange(file, event, origin, sibling, provenance()));

		AppliedLocationChange replay = catalogLocationWriter
				.rename(new LocationChange(file, event, origin, sibling, provenance()));

		Assertions.assertThat(replay.replayed()).isTrue();
		Assertions.assertThat(replay.currentPath()).isEqualTo(PathUtils.normalize(sibling));
		Assertions.assertThat(eventCountOf(event)).isEqualTo(1);
	}

	/**
	 * The ordering that makes a retry safe. A caller retrying after a lost response
	 * describes the world as it was before its own successful write; judged against
	 * the world as it is now, that would be refused for having worked.
	 */
	@Test
	void aRetryIsStillARetryAfterTheFileHasMovedOnAgain() {
		long file = seed(origin, "ACTIVE");
		UUID event = UUID.randomUUID();
		Path third = library.resolve("terceiro.jpg");

		catalogLocationWriter.rename(new LocationChange(file, event, origin, sibling, provenance()));
		catalogLocationWriter.rename(change(file, sibling, third));

		AppliedLocationChange replay = catalogLocationWriter
				.rename(new LocationChange(file, event, origin, sibling, provenance()));

		Assertions.assertThat(replay.replayed()).isTrue();
		Assertions.assertThat(replay.currentPath()).isEqualTo(PathUtils.normalize(third));
	}

	@Test
	void twoDifferentChangesCannotClaimOneIdentity() {
		long file = seed(origin, "ACTIVE");
		UUID event = UUID.randomUUID();

		catalogLocationWriter.rename(new LocationChange(file, event, origin, sibling, provenance()));

		assertRefused(
				() -> catalogLocationWriter.rename(new LocationChange(file, event, sibling,
						library.resolve("outro.jpg"), provenance())),
				LocationChangeFailure.IDEMPOTENCY_CONFLICT);
	}

	/**
	 * The state the catalog is not supposed to be able to reach, seeded here by
	 * writing round the door. Reported rather than chosen between: picking one
	 * would hand a file's fingerprints and exclusions to another and leave no
	 * trace.
	 */
	@Test
	void twoPresentFilesAtOnePathAreReportedRatherThanChosenBetween() {
		seed(sibling, "ACTIVE");
		seed(sibling, "ACTIVE");
		long moving = seed(origin, "ACTIVE");

		assertRefused(() -> catalogLocationWriter.rename(change(moving, origin, sibling)),
				LocationChangeFailure.MULTIPLE_PRESENT_FILES);
	}

	/**
	 * The refusal, taken without losing the transaction it happened in.
	 *
	 * <p>
	 * PostgreSQL aborts a transaction on error and refuses every command after it
	 * until the block ends, so a check made afterwards would fail on the check
	 * instead of on what it meant to assert. The savepoint releases exactly the
	 * failed statement and leaves the rest of the transaction alive - the rows
	 * seeded before it, and the reads that come after.
	 */
	private void assertRefused(ThrowingCallable call, LocationChangeFailure expected) {
		jdbcTemplate.execute((Connection connection) -> {
			Savepoint beforeTheRefusal = connection.setSavepoint("refusal");

			Assertions.assertThatThrownBy(call).isInstanceOfSatisfying(LocationChangeException.class,
					refusal -> Assertions.assertThat(refusal.getFailure()).isEqualTo(expected));

			connection.rollback(beforeTheRefusal);

			return null;
		});
	}

	private LocationChange change(long catalogFileId, Path from, Path to) {
		return new LocationChange(catalogFileId, UUID.randomUUID(), from, to, provenance());
	}

	private long seed(Path path, String lifecycleStatus) {
		long file = seedWithoutLocation(lifecycleStatus);

		jdbcTemplate.update("""
				INSERT INTO catalog_file_location (catalog_file_id, current_path, path_flavor)
				VALUES (?, ?, ?)
				""", file, PathUtils.normalize(path), PathFlavor.of(path).name());

		return file;
	}

	private long seedWithoutLocation() {
		return seedWithoutLocation("ACTIVE");
	}

	private long seedWithoutLocation(String lifecycleStatus) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO catalog_file (catalog_file_public_id, extension, size_bytes, modified_at, file_type,
				        lifecycle_status)
				VALUES (?, 'jpg', 1024, now(), 'PHOTO', ?)
				RETURNING id
				""", Long.class, UUID.randomUUID(), lifecycleStatus);
	}

	private String currentPathOf(long catalogFileId) {
		return text("SELECT current_path FROM catalog_file_location WHERE catalog_file_id = ?", catalogFileId);
	}

	private String eventTypeOf(UUID event) {
		return text("SELECT event_type FROM catalog_file_event WHERE catalog_file_event_public_id = ?", event);
	}

	private String oldPathOf(UUID event) {
		return text("SELECT old_path FROM catalog_file_event WHERE catalog_file_event_public_id = ?", event);
	}

	private long eventCountOf(UUID event) {
		return jdbcTemplate.queryForObject(
				"SELECT count(*) FROM catalog_file_event WHERE catalog_file_event_public_id = ?", Long.class, event);
	}

	/** Null rather than an exception where the row is what the case is about. */
	private String text(String sql, Object argument) {
		List<String> found = jdbcTemplate.queryForList(sql, String.class, argument);

		return found.isEmpty() ? null : found.getFirst();
	}

	/**
	 * What the catalog knows about a change of ours: when it happened, who did it
	 * and on what grounds. They travel as one object because they are one answer -
	 * a fact with a source but no evidence would be a fact nobody can weigh.
	 */
	private CatalogFactProvenance provenance() {
		return provenance(Instant.now());
	}

	private CatalogFactProvenance provenance(Instant occurredAt) {
		return new CatalogFactProvenance(occurredAt, SOURCE, CatalogEventEvidence.NIMBUS_OPERATION, null);
	}
}