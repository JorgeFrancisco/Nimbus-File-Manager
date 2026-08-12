package br.com.jorgemelo.nimbusfilemanager.inventory.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationReconcileApply;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.CatalogFactProvenance;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.CatalogLifecycleWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * What the catalog should understand when a file turns up at a path it already
 * has an entry for.
 *
 * <p>
 * The whole rule lives in {@code CatalogPathMatcher}, and it is a rule about
 * two different things that look identical from the disk: an entry the catalog
 * <em>lost</em> may be met again and is the same file, while an entry the user
 * <em>removed</em> is a decision, and a scan of a folder is not the place to
 * undo it. Quarantining a photograph and saving a new one under the same name
 * used to hand the new bytes to the old entry and bring it back, silently.
 *
 * <p>
 * Driven through a real inventory pass against a real PostgreSQL rather than
 * against the matcher alone: what decides identity here is the matcher, the
 * lifecycle the rows carry and the queries that read them together, and a test
 * of the matcher on its own would prove only that one third of that agrees with
 * itself.
 */
@SpringBootTest
@Testcontainers
@Import(InventoryBatchTestSeeder.class)
// A real inventory pass takes the global operation lock, as production does.
@ResourceLock("inventory-batch")
class DiscoveryAtAKnownPathIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

	private static final Path WORKSPACE = createWorkspace();

	@Autowired
	private InventoryBatchTestSeeder inventorySeeder;

	@Autowired
	private OrganizationReconcileApply organizationReconcileApply;

	@Autowired
	private CatalogLifecycleWriter catalogLifecycleWriter;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * A removed entry is not a candidate for what turns up at its last address.
	 *
	 * <p>
	 * The file the scan finds is one the catalog is meeting for the first time,
	 * and the removal stays a removal - with its own history, at the path it was
	 * last seen at, which is what a screen shows when asked where the file used to
	 * be.
	 */
	@Test
	void aRemovedEntryIsNotMetAgainAndWhatArrivesIsANewFile() throws IOException {
		Path folder = Files.createDirectories(WORKSPACE.resolve("removed-" + System.nanoTime()));
		Path place = Files.writeString(folder.resolve("photo.jpg"), "the one the user removed");

		inventorySeeder.seed(inventory(folder));

		long removed = onlyFileAt(place);

		catalogLifecycleWriter.markDeleted(List.of(removed), because(CatalogEventSources.QUARANTINE));

		// A different file, at the same place, with bytes of its own.
		Files.writeString(place, "a photograph that has never been catalogued");

		inventorySeeder.seed(inventory(folder));

		List<Long> atThePlace = idsAt(place);

		Assertions.assertThat(atThePlace).as("the removal and the newcomer, not one entry doing both").hasSize(2);

		long arrived = atThePlace.stream().filter(id -> id != removed).findFirst().orElseThrow();

		Assertions.assertThat(lifecycleOf(removed)).as("a scan of a folder does not undo a removal")
				.isEqualTo(LifecycleStatus.DELETED.name());
		Assertions.assertThat(lifecycleOf(arrived)).isEqualTo(LifecycleStatus.ACTIVE.name());
		Assertions.assertThat(pathOf(removed)).as("the removed entry keeps saying where it was")
				.isEqualTo(PathUtils.normalize(place));
		Assertions.assertThat(pathOf(arrived)).isEqualTo(PathUtils.normalize(place));

		Assertions.assertThat(eventTypesOf(removed))
				.as("nothing was said about the removed entry by a pass that found somebody else's file")
				.containsExactly("DELETED");
	}

	/**
	 * The other half of the same disk state: an entry the catalog lost is the same
	 * file when it comes back, and the timeline says both halves out loud.
	 */
	@Test
	void anEntryTheCatalogLostIsTheSameFileWhenItComesBack() throws IOException {
		Path folder = Files.createDirectories(WORKSPACE.resolve("lost-" + System.nanoTime()));
		Path place = Files.writeString(folder.resolve("holiday.jpg"), "content that will be back");

		inventorySeeder.seed(inventory(folder));

		long file = onlyFileAt(place);

		Files.delete(place);

		organizationReconcileApply.reconcileAndApply(new OrganizationReconcileRequest(folder.toString(), true, false,
				100));

		Assertions.assertThat(lifecycleOf(file)).isEqualTo(LifecycleStatus.MISSING.name());

		Files.writeString(place, "content that will be back");

		inventorySeeder.seed(inventory(folder));

		Assertions.assertThat(idsAt(place)).as("the same file, not a second entry for it").containsExactly(file);
		Assertions.assertThat(lifecycleOf(file)).isEqualTo(LifecycleStatus.ACTIVE.name());

		// Both halves are facts, in the order they happened, each said by whatever
		// established it: a pass over the disk found nothing where the catalog
		// expected something, and a later one found something there again.
		Assertions.assertThat(historyOf(file)).containsSubsequence(
				Map.of("type", "MISSING", "source", CatalogEventSources.RECONCILE, "evidence",
						CatalogEventEvidence.PATH_NOT_FOUND),
				Map.of("type", "REAPPEARED", "source", CatalogEventSources.INVENTORY, "evidence",
						CatalogEventEvidence.PATH_FOUND));
	}

	private InventoryRequest inventory(Path folder) {
		return new InventoryRequest(folder.toString(), true, false, true, true);
	}

	private CatalogFactProvenance because(String source) {
		return new CatalogFactProvenance(Instant.now(), source, CatalogEventEvidence.NIMBUS_OPERATION, null);
	}

	private long onlyFileAt(Path place) {
		List<Long> ids = idsAt(place);

		Assertions.assertThat(ids).as("the pass catalogued exactly the file it was pointed at").hasSize(1);

		return ids.getFirst();
	}

	private List<Long> idsAt(Path place) {
		return jdbcTemplate.queryForList("SELECT catalog_file_id FROM catalog_file_location WHERE current_path = ?"
				+ " ORDER BY catalog_file_id", Long.class, PathUtils.normalize(place));
	}

	private String lifecycleOf(long catalogFileId) {
		return jdbcTemplate.queryForObject("SELECT lifecycle_status FROM catalog_file WHERE id = ?", String.class,
				catalogFileId);
	}

	private String pathOf(long catalogFileId) {
		return jdbcTemplate.queryForObject("SELECT current_path FROM catalog_file_location WHERE catalog_file_id = ?",
				String.class, catalogFileId);
	}

	private List<String> eventTypesOf(long catalogFileId) {
		return jdbcTemplate.queryForList(
				"SELECT event_type FROM catalog_file_event WHERE catalog_file_id = ? ORDER BY id", String.class,
				catalogFileId);
	}

	private List<Map<String, Object>> historyOf(long catalogFileId) {
		return jdbcTemplate.queryForList("SELECT event_type AS type, source, evidence_kind AS evidence"
				+ " FROM catalog_file_event WHERE catalog_file_id = ? ORDER BY id", catalogFileId);
	}

	private static Path createWorkspace() {
		try {
			return Files.createTempDirectory("nimbus-file-manager-discovery-");
		} catch (IOException e) {
			throw new IllegalStateException("Could not create test workspace", e);
		}
	}
}