package br.com.jorgemelo.nimbusfilemanager.organization.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * The two sides of a reconcile have to describe the same universe, and this is
 * the test that says so against a real database.
 *
 * <p>
 * A shallow pass lists one folder on disk. While the catalog side was read as
 * "this folder and everything under it" regardless, every catalogued file in
 * every subfolder was compared against a disk listing that could never contain
 * it, concluded missing, and marked so - and, once the retention window passed,
 * deleted. Reachable from the {@code watch-recursive} setting alone, and silent,
 * because a reconcile marking things missing is a reconcile doing its job.
 *
 * <p>
 * Against Postgres rather than a mock on purpose: what changed is which query
 * the pass asks, so a test that stubs the answer would prove only that the stub
 * was returned.
 */
@SpringBootTest
@Transactional
@Testcontainers
class ShallowReconcileScopeIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

	@TempDir
	Path library;

	@Autowired
	private OrganizationReconcileApply organizationReconcileApply;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@PersistenceContext
	private EntityManager entityManager;

	@Test
	void aShallowPassLeavesCataloguedFilesInSubfoldersAlone() throws IOException {
		Path folder = Files.createDirectory(library.resolve("shallow"));
		Path subfolder = Files.createDirectory(folder.resolve("sub"));

		Long present = catalogued(Files.writeString(folder.resolve("present.jpg"), "here"));
		Long gone = catalogued(folder.resolve("gone.jpg"));
		Long deep = catalogued(subfolder.resolve("deep.jpg"));

		organizationReconcileApply
				.reconcileAndApply(new OrganizationReconcileRequest(folder.toString(), false, false, 10));

		Assertions.assertThat(lifecycleOf(deep))
				.as("a file the shallow walk never looked at cannot have been found missing by it")
				.isEqualTo(LifecycleStatus.ACTIVE);
		Assertions.assertThat(lifecycleOf(gone)).as("the folder's own missing file is still found")
				.isEqualTo(LifecycleStatus.MISSING);
		Assertions.assertThat(lifecycleOf(present)).isEqualTo(LifecycleStatus.ACTIVE);
	}

	@Test
	void aRecursivePassStillMarksWhatVanishedDeepInTheTree() throws IOException {
		Path folder = Files.createDirectory(library.resolve("recursive"));
		Path subfolder = Files.createDirectory(folder.resolve("sub"));

		Long present = catalogued(Files.writeString(subfolder.resolve("present.jpg"), "here"));
		Long deep = catalogued(subfolder.resolve("deep.jpg"));

		organizationReconcileApply
				.reconcileAndApply(new OrganizationReconcileRequest(folder.toString(), true, false, 10));

		Assertions.assertThat(lifecycleOf(deep)).as("a recursive pass reaches the whole tree, as it always did")
				.isEqualTo(LifecycleStatus.MISSING);
		Assertions.assertThat(lifecycleOf(present)).isEqualTo(LifecycleStatus.ACTIVE);
	}

	/**
	 * Read from the row rather than from the session.
	 *
	 * <p>
	 * The lifecycle door writes by JDBC, in the transaction this test runs in but
	 * not through its persistence context - so an entity this class loaded still
	 * answers what it was loaded as, and a pass that really did mark the file
	 * missing reads back as if it had done nothing.
	 */
	private LifecycleStatus lifecycleOf(Long catalogFileId) {
		entityManager.clear();

		return catalogFileRepository.findById(catalogFileId).orElseThrow().getLifecycleStatus();
	}

	/**
	 * A catalogued file at {@code path}, which may or may not exist on disk - the
	 * absent ones are the whole subject here.
	 */
	private Long catalogued(Path path) {
		String key = PathUtils.normalize(path);

		String folder = PathUtils.normalize(path.getParent());

		CatalogFile file = CatalogFile.builder().extension("jpg")
				.sizeBytes(4L).modifiedAt(Instant.now()).fileType(FileType.PHOTO)
				.lifecycleStatus(LifecycleStatus.ACTIVE).build();
		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(key).currentFolder(folder)
				.pathFlavor(PathFlavor.WINDOWS).build());

		return CatalogFiles.catalogued(catalogFileRepository, catalogFileLocationRepository, file).getId();
	}
}