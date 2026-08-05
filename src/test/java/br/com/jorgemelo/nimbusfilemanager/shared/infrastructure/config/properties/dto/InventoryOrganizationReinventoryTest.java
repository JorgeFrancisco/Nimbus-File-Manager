package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.CatalogExportService;
import br.com.jorgemelo.nimbusfilemanager.catalog.domain.enums.CatalogExportFormat;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.InventoryBatchTestSeeder;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationJobTestRunner;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.settings.application.LibraryCatalogCleanupService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.TimelineFilter;
import br.com.jorgemelo.nimbusfilemanager.timeline.infrastructure.persistence.TimelineQueryRepository;

@SpringBootTest
@Testcontainers
@Import({ InventoryBatchTestSeeder.class, OrganizationJobTestRunner.class })
// Drives the real Spring Batch inventory job, which enforces a single active
// inventory at a time (global operation lock + shared JobRepository). Serialize
// against the other inventory-driving integration tests so the concurrent
// class-level test execution never runs two inventory jobs at once.
@ResourceLock("inventory-batch")
class InventoryOrganizationReinventoryTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	private static final Path WORKSPACE = createWorkspace();

	@Autowired
	private InventoryBatchTestSeeder inventorySeeder;

	@Autowired
	private OrganizationJobTestRunner organizationJobTestRunner;

	@Autowired
	private TimelineQueryRepository timelineQueryRepository;

	@Autowired
	private LibraryCatalogCleanupService libraryCatalogCleanupService;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogExportService catalogExportService;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) throws IOException {
		Files.createDirectories(WORKSPACE.resolve("database"));
		registry.add("nimbus-file-manager.workspace", WORKSPACE::toString);
	}

	@Test
	void inventoryShouldUpdateExistingFileAfterOrganizationWithoutLazyInitialization() throws Exception {
		Path source = Files.createDirectories(WORKSPACE.resolve("temp"));
		Path target = Files.createDirectories(WORKSPACE.resolve("organized"));
		Path firstFolder = Files.createDirectories(source.resolve("first"));
		Path secondFolder = Files.createDirectories(source.resolve("second"));
		Path firstFile = firstFolder.resolve("same-name.txt");
		Path secondFile = secondFolder.resolve("same-name.txt");
		Path uniqueFile = source.resolve("unique-name.txt");

		Files.writeString(firstFile, "first");
		Files.writeString(secondFile, "second");
		Files.writeString(uniqueFile, "unique");

		var inventory = inventorySeeder.seed(new InventoryRequest(source.toString(), true, false, true, true));

		Assertions.assertThat(inventory.filesFound()).isEqualTo(3);
		Assertions.assertThat(inventory.filesAnalyzed()).isEqualTo(3);
		Assertions.assertThat(inventory.errors()).isZero();

		var organization = organizationJobTestRunner
				.organize(new OrganizationExecuteRequest(source.toString(), target.toString(), true,
						OrganizationLayout.DEFAULT, 10000, false, null, true, null, null, null, null, true, false));

		// Read from the row rather than from a return value: the move happens in the
		// worker now, and the row is what the screen and every later question see.
		Assertions.assertThat(organization.getFilesMoved()).isEqualTo(1);
		Assertions.assertThat(organization.getCacheHits()).isEqualTo(2);
		Assertions.assertThat(organization.getErrors()).isZero();

		Path remainingFile = Files.exists(firstFile) ? firstFile : secondFile;

		Assertions.assertThat(Files.exists(remainingFile)).isTrue();

		Files.writeString(remainingFile, "second inventory");

		var reinventory = inventorySeeder.seed(new InventoryRequest(source.toString(), true, false, true, true));

		Assertions.assertThat(reinventory.filesFound()).isEqualTo(2);
		Assertions.assertThat(reinventory.filesAnalyzed()).isEqualTo(2);
		Assertions.assertThat(reinventory.errors()).isZero();
		Assertions.assertThat(countFiles(target)).isEqualTo(1);
	}

	@Test
	void organizationShouldBeIdempotentAndNonDuplicatingOnReRun() throws Exception {
		Path base = Files.createDirectories(WORKSPACE.resolve("idempotent-" + System.nanoTime()));
		Path source = Files.createDirectories(base.resolve("source"));
		Path target = Files.createDirectories(base.resolve("organized"));

		Files.writeString(source.resolve("a.txt"), "alpha-" + System.nanoTime());
		Files.writeString(source.resolve("b.txt"), "bravo-" + System.nanoTime());

		inventorySeeder.seed(new InventoryRequest(source.toString(), true, false, true, true));

		var first = organizationJobTestRunner.organize(new OrganizationExecuteRequest(source.toString(),
				target.toString(), true, OrganizationLayout.DEFAULT, 10000, false, null, true, null, null, null, null,
				true, false));

		Assertions.assertThat(first.getFilesMoved()).isEqualTo(2);
		Assertions.assertThat(first.getErrors()).isZero();

		long filesAfterFirst = countFiles(target);

		Assertions.assertThat(filesAfterFirst).isEqualTo(2);

		// Re-run the identical organization: the files already left the source, so this
		// must be a clean, idempotent no-op - no moves, no errors, no rejection, and
		// the
		// target is never duplicated.
		var second = organizationJobTestRunner.organize(new OrganizationExecuteRequest(source.toString(),
				target.toString(), true, OrganizationLayout.DEFAULT, 10000, false, null, true, null, null, null, null,
				true, false));

		Assertions.assertThat(second.getFilesMoved()).isZero();
		Assertions.assertThat(second.getErrors()).isZero();
		Assertions.assertThat(second.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
		Assertions.assertThat(countFiles(target)).isEqualTo(filesAfterFirst);
	}

	@Test
	void catalogExportShouldStreamInventoriedFilesAsCsvOnPostgres() throws Exception {
		Path source = Files.createDirectories(WORKSPACE.resolve("catalog-export-" + System.nanoTime()));
		Path file = Files.writeString(source.resolve("exported.txt"), "payload-" + System.nanoTime());

		inventorySeeder.seed(new InventoryRequest(source.toString(), true, false, true, true));

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		catalogExportService.export(CatalogExportFormat.CSV).body().writeTo(out);
		String csv = out.toString(StandardCharsets.UTF_8);

		// Validates the real keyset/LEFT JOIN/CAST query and that the inventoried file
		// is present with its placement.
		Assertions.assertThat(csv).startsWith("publicId,fileKey,fileName").contains(PathUtils.normalize(file))
				.contains("exported.txt");
	}

	@Test
	void timelineQueriesShouldAcceptNullOptionalParametersOnPostgres() {
		List<String> subcategories = List.of("CAMERA", "CELLPHONE");

		Assertions.assertThatCode(() -> {
			timelineQueryRepository.findCountSummary(null, subcategories, TimelineFilter.NONE);
			timelineQueryRepository.findMonthCounts(null, subcategories, TimelineFilter.NONE);
			timelineQueryRepository.findPage(null, subcategories, TimelineFilter.NONE, null, null, 2);
			timelineQueryRepository.findUndatedPage(null, subcategories, TimelineFilter.NONE, null, 2);
		}).doesNotThrowAnyException();
	}

	@Test
	void libraryCleanupShouldRemoveOnlyThePreviousFolderCatalogOnPostgres() throws Exception {
		Path oldLibrary = Files.createDirectories(WORKSPACE.resolve("cleanup-old-" + System.nanoTime()));
		Path retainedLibrary = Files.createDirectories(WORKSPACE.resolve("cleanup-retained-" + System.nanoTime()));
		Path oldFile = Files.writeString(oldLibrary.resolve("old.txt"), "old");
		Path retainedFile = Files.writeString(retainedLibrary.resolve("retained.txt"), "retained");

		inventorySeeder.seed(new InventoryRequest(oldLibrary.toString(), true, false, true, true));
		inventorySeeder.seed(new InventoryRequest(retainedLibrary.toString(), true, false, true, true));

		int removed = libraryCatalogCleanupService.clear(oldLibrary.toString());

		Assertions.assertThat(removed).isEqualTo(1);
		Assertions.assertThat(catalogFileRepository.findByFileKey(PathUtils.normalize(oldFile))).isEmpty();
		Assertions.assertThat(catalogFileRepository.findByFileKey(PathUtils.normalize(retainedFile))).isPresent();
	}

	private static long countFiles(Path folder) throws IOException {
		try (Stream<Path> paths = Files.walk(folder)) {
			return paths.filter(Files::isRegularFile).count();
		}
	}

	private static Path createWorkspace() {
		try {
			return Files.createTempDirectory("nimbus-file-manager-inventory-organization-");
		} catch (IOException e) {
			throw new IllegalStateException("Could not create test workspace", e);
		}
	}
}