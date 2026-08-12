package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.InventoryBatchTestSeeder;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Proves the reliability invariant that motivated the reconciliation work: when
 * a file is moved on disk but the database update never lands (a crash between
 * {@code Files.move} and the catalog commit), the system must never stay
 * <em>silently</em> inconsistent. Reconciliation either relocates the catalog
 * to the file's real new location, or, when it cannot pair the move, marks the
 * record MISSING - in both cases the divergence is surfaced, not hidden.
 */
@SpringBootTest
@Testcontainers
@Import(InventoryBatchTestSeeder.class)
// Drives the real Spring Batch inventory job, which enforces a single active
// inventory at a time (global operation lock + shared JobRepository). Serialize
// against the other inventory-driving integration tests so the concurrent
// class-level test execution never runs two inventory jobs at once.
@ResourceLock("inventory-batch")
class OrganizationCrashRecoveryIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

	private static final Path WORKSPACE = createWorkspace();

	@Autowired
	private InventoryBatchTestSeeder inventorySeeder;

	@Autowired
	private OrganizationReconcileApply organizationReconcileApply;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) throws IOException {
		Files.createDirectories(WORKSPACE.resolve("database"));

		registry.add("nimbus-file-manager.workspace", WORKSPACE::toString);
	}

	@Test
	void reconcileShouldRelocateCatalogWhenFileMovedOnDiskButDatabaseUpdateNeverHappened() throws Exception {
		Path root = Files.createDirectories(WORKSPACE.resolve("crash-relocate-" + System.nanoTime()));
		Path sourceDir = Files.createDirectories(root.resolve("source"));
		Path organizedDir = Files.createDirectories(root.resolve("organized"));
		Path original = Files.writeString(sourceDir.resolve("photo.txt"),
				"the-exact-bytes-that-moved-" + System.nanoTime());

		inventorySeeder.seed(new InventoryRequest(sourceDir.toString(), true, false, true, true));

		String originalKey = PathUtils.normalize(original);

		Assertions.assertThat(catalogFileLocationRepository.findPresentByPath(originalKey, PathFlavor.current().name())).isPresent();

		// Crash simulation: the physical move happened, the catalog update did NOT.
		Path moved = organizedDir.resolve("202401").resolve("09").resolve("photo.txt");

		Files.createDirectories(moved.getParent());
		Files.move(original, moved);

		Assertions.assertThat(Files.exists(original)).isFalse();
		Assertions.assertThat(catalogFileLocationRepository.findPresentByPath(originalKey, PathFlavor.current().name())).isPresent();

		organizationReconcileApply
				.reconcileAndApply(new OrganizationReconcileRequest(root.toString(), true, false, 100));

		// The catalog followed the file to its real location instead of dangling.
		String movedKey = PathUtils.normalize(moved);

		Assertions.assertThat(catalogFileLocationRepository.findPresentByPath(movedKey, PathFlavor.current().name())).isPresent().get()
				.extracting(CatalogFile::getLifecycleStatus).isEqualTo(LifecycleStatus.ACTIVE);
		Assertions.assertThat(catalogFileLocationRepository.findPresentByPath(originalKey, PathFlavor.current().name())).isEmpty();
	}

	@Test
	void reconcileShouldMarkMissingWhenMovedFileIsOutsideReconcileScope() throws Exception {
		Path root = Files.createDirectories(WORKSPACE.resolve("crash-missing-" + System.nanoTime()));
		Path sourceDir = Files.createDirectories(root.resolve("source"));
		Path outside = Files.createDirectories(WORKSPACE.resolve("outside-" + System.nanoTime()));
		Path original = Files.writeString(sourceDir.resolve("clip.txt"), "moved-out-of-scope-" + System.nanoTime());

		inventorySeeder.seed(new InventoryRequest(sourceDir.toString(), true, false, true, true));

		String originalKey = PathUtils.normalize(original);

		Assertions.assertThat(catalogFileLocationRepository.findPresentByPath(originalKey, PathFlavor.current().name())).isPresent().get()
				.extracting(CatalogFile::getLifecycleStatus).isEqualTo(LifecycleStatus.ACTIVE);

		// Crash simulation: file moved out of the reconcile scope, catalog untouched.
		Files.move(original, outside.resolve("clip.txt"));

		organizationReconcileApply
				.reconcileAndApply(new OrganizationReconcileRequest(sourceDir.toString(), true, false, 100));

		// It cannot be relocated (the new file is outside the scanned scope), but the
		// divergence is detected: the record is flagged MISSING, never left ACTIVE at a
		// path that no longer exists.
		// Asked of the door that answers for a file whether or not it is still there:
		// "present" is exactly what this file stopped being.
		Assertions.assertThat(catalogFileLocationRepository.findLastKnownByPath(originalKey,
				PathFlavor.current().name())).singleElement()
				.extracting(CatalogFile::getLifecycleStatus).isEqualTo(LifecycleStatus.MISSING);
	}

	private static Path createWorkspace() {
		try {
			return Files.createTempDirectory("nimbus-file-manager-crash-recovery-");
		} catch (IOException e) {
			throw new IllegalStateException("Could not create test workspace", e);
		}
	}
}