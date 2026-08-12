package br.com.jorgemelo.nimbusfilemanager.inventory.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * A walk that meets a catalogued file whose bytes may have moved.
 *
 * <p>
 * This is the case that broke in production and that four thousand green tests
 * did not reach, because reaching it takes two passes over the same folder:
 * the first has an empty catalog, so every file is new and the branch is never
 * entered. The second finds the entry, sees the size or timestamp disagree with
 * what was recorded, and asks for the content to be verified.
 *
 * <p>
 * Asking used to happen inside the cache check, which runs in a read-only
 * transaction because it is a lookup the whole walk repeats. PostgreSQL refused
 * the insert - {@code cannot execute INSERT in a read-only transaction},
 * SQLState 25006 - and took the inventory down with it thirty seconds in.
 *
 * <p>
 * Against a real PostgreSQL and through the real inventory pass, because
 * read-only is a property of the transaction the database opened: a mocked
 * repository accepts the write, an in-memory database has no such mode, and
 * either one would prove nothing about the thing that failed.
 */
@SpringBootTest
@Testcontainers
@Import(InventoryBatchTestSeeder.class)
// A real inventory pass takes the global operation lock, as production does.
@ResourceLock("inventory-batch")
class ContentSuspicionDuringInventoryIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

	private static final Path WORKSPACE = createWorkspace();

	@Autowired
	private InventoryBatchTestSeeder inventorySeeder;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * The reproduction. Before the fix this fails with SQLState 25006 raised from
	 * the cache check; after it, the walk completes and the verification exists.
	 */
	@Test
	void aCataloguedFileWhoseFactsMovedIsAskedAboutWithoutWritingInsideTheRead() throws IOException {
		Path folder = folder("suspect");
		Path place = Files.writeString(folder.resolve("photo.jpg"), "the bytes the catalog knows");

		inventorySeeder.seed(inventory(folder));

		long file = onlyFileAt(place);

		// Longer content: the size on disk stops matching the size on record, which is
		// what the cheap check compares. No digest is read here - that is the
		// verification's job, and asking for it is the whole point.
		Files.writeString(place, "bytes that are not the bytes the catalog knows about at all");

		inventorySeeder.seed(inventory(folder));

		Assertions.assertThat(verificationsFor(place)).as("the walk asked for exactly one verification").hasSize(1);
		Assertions.assertThat(dedupKeyOf(place)).as("and named the catalogued file it is about")
				.isEqualTo("content-verification:" + file);
	}

	/**
	 * The path is not decoration: a verification is excluded by the place it
	 * reads, and one that names none is failed by the dispatcher before it runs -
	 * which is how an earlier defect left divergences that could never converge.
	 */
	@Test
	void theRequestNamesThePlaceItHasToRead() throws IOException {
		Path folder = folder("named");
		Path place = Files.writeString(folder.resolve("holiday.jpg"), "first");

		inventorySeeder.seed(inventory(folder));

		Files.writeString(place, "second, and a good deal longer than the first");

		inventorySeeder.seed(inventory(folder));

		Assertions.assertThat(sourcePathsOf(place)).singleElement().isEqualTo(PathUtils.normalize(place));
	}

	/** A walk over files nothing touched asks for nothing. */
	@Test
	void aWalkThatFindsEverythingAsItLeftItAsksForNoVerification() throws IOException {
		Path folder = folder("settled");
		Path place = Files.writeString(folder.resolve("unchanged.jpg"), "untouched between the two passes");

		inventorySeeder.seed(inventory(folder));
		inventorySeeder.seed(inventory(folder));

		Assertions.assertThat(verificationsFor(place)).isEmpty();
	}

	/** Several files moved: every one of them is represented, none merged away. */
	@Test
	void everySuspectInTheBatchGetsItsOwnRequest() throws IOException {
		Path folder = folder("several");
		Path first = Files.writeString(folder.resolve("a.jpg"), "a");
		Path second = Files.writeString(folder.resolve("b.jpg"), "b");
		Path third = Files.writeString(folder.resolve("c.jpg"), "c");

		inventorySeeder.seed(inventory(folder));

		Files.writeString(first, "a grew considerably");
		Files.writeString(second, "b grew considerably as well");

		inventorySeeder.seed(inventory(folder));

		Assertions.assertThat(verificationsFor(first)).hasSize(1);
		Assertions.assertThat(verificationsFor(second)).hasSize(1);
		Assertions.assertThat(verificationsFor(third)).as("this one never moved").isEmpty();
	}

	/**
	 * Found again by a later walk while the first request is still waiting: the
	 * queue's deduplication is the authority, and it is not bypassed by asking
	 * from a different place than before.
	 */
	@Test
	void findingTheSameSuspectAgainDoesNotPileUpRequests() throws IOException {
		Path folder = folder("again");
		Path place = Files.writeString(folder.resolve("twice.jpg"), "one");

		inventorySeeder.seed(inventory(folder));

		Files.writeString(place, "two, which is longer than one");

		inventorySeeder.seed(inventory(folder));
		inventorySeeder.seed(inventory(folder));

		Assertions.assertThat(verificationsFor(place)).as("the request already waiting is the request").hasSize(1);
	}

	/**
	 * The verification an earlier pass asked for is still waiting when the file
	 * moves again - the ordinary case whenever the queue is behind, since nothing
	 * verifies content synchronously. The second request is a duplicate of the
	 * first, and the batch that makes it has to survive being told so.
	 */
	@Test
	void aSuspectWhoseVerificationIsStillWaitingDoesNotCostTheBatch() throws IOException {
		Path folder = folder("waiting");
		Path place = Files.writeString(folder.resolve("busy.jpg"), "one");

		inventorySeeder.seed(inventory(folder));

		Files.writeString(place, "two, which is longer than one");

		inventorySeeder.seed(inventory(folder));

		Assertions.assertThat(verificationsFor(place)).as("the earlier pass asked").hasSize(1);

		Files.writeString(place, "three, longer again, and nothing has verified the second one yet");

		inventorySeeder.seed(inventory(folder));

		Assertions.assertThat(verificationsFor(place)).as("the request already waiting is still the request")
				.hasSize(1);

		// The count above cannot tell a surviving batch from a lost one - the earlier
		// request stays either way. What the file measures is whether the write that
		// asked the second time committed, which is the thing a refusal must not cost.
		Assertions.assertThat(sizeOnRecordOf(place)).as("and the batch that was refused still recorded what it saw")
				.isEqualTo(Files.size(place));
	}

	private Path folder(String name) throws IOException {
		return Files.createDirectories(WORKSPACE.resolve(name + "-" + System.nanoTime()));
	}

	private InventoryRequest inventory(Path folder) {
		return new InventoryRequest(folder.toString(), true, false, false, false);
	}

	private long onlyFileAt(Path place) {
		return jdbcTemplate.queryForObject(
				"SELECT catalog_file_id FROM catalog_file_location WHERE current_path = ?", Long.class,
				PathUtils.normalize(place));
	}

	private List<Long> verificationsFor(Path place) {
		return jdbcTemplate.queryForList("SELECT id FROM execution WHERE execution_type = ? AND source_path = ?",
				Long.class, ExecutionType.CONTENT_VERIFICATION.name(), PathUtils.normalize(place));
	}

	/** What the catalog believes the file weighs; a lost batch never updates it. */
	private long sizeOnRecordOf(Path place) {
		return jdbcTemplate.queryForObject("""
				SELECT file.size_bytes FROM catalog_file file
				JOIN catalog_file_location place ON place.catalog_file_id = file.id
				WHERE place.current_path = ?
				""", Long.class, PathUtils.normalize(place));
	}

	private List<String> sourcePathsOf(Path place) {
		return jdbcTemplate.queryForList(
				"SELECT source_path FROM execution WHERE execution_type = ? AND source_path = ?", String.class,
				ExecutionType.CONTENT_VERIFICATION.name(), PathUtils.normalize(place));
	}

	/**
	 * Which catalogued file the request is about, read from the request itself
	 * rather than from the catalog: the identifier travels in the deduplication
	 * key, which is what makes a second sighting of the same file the same request
	 * instead of another one.
	 */
	private String dedupKeyOf(Path place) {
		return jdbcTemplate.queryForObject(
				"SELECT dedup_key FROM execution WHERE execution_type = ? AND source_path = ?", String.class,
				ExecutionType.CONTENT_VERIFICATION.name(), PathUtils.normalize(place));
	}

	private static Path createWorkspace() {
		try {
			return Files.createTempDirectory("nimbus-content-suspicion");
		} catch (IOException e) {
			throw new IllegalStateException("Could not create the test workspace", e);
		}
	}
}