package br.com.jorgemelo.nimbusfilemanager.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.CatalogTimestamp;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.ScannedFile;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataOptions;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.model.MetadataResult;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionMetricsContext;

/**
 * A file and where it was found are one write.
 *
 * <p>
 * The placement stopped travelling with the aggregate, so the pass that
 * catalogues a file now writes it and then writes where it is. That is two
 * statements, and what makes them one fact is the transaction around them - a
 * file on record at no place is not a lesser record, it is a file the catalog
 * cannot find, cannot organize and cannot tell you about.
 *
 * <p>
 * The single-file path had no transaction of its own until this was measured:
 * the file committed alone and the placement was written against a row from a
 * transaction that had already closed. It reached the product through
 * conversion, which catalogues its output one file at a time.
 *
 * <p>
 * Runs against a database of its own, because a rollback is only observable
 * from outside the transaction that performed it.
 */
@SpringBootTest
@Testcontainers
class InitialPlacementIntegrationTest {

	/** This test's own context: nothing here is shared with another run. */
	private final ExecutionMetricsContext context = new ExecutionMetricsContext();

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

	private static final MetadataOptions ANALYSE = new MetadataOptions(false, false);

	@Autowired
	private InventoryPersistenceService inventoryPersistenceService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/** The door the placement goes through, made to fail where a database would. */
	@MockitoSpyBean
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Test
	void aPlacementThatCouldNotBeWrittenTakesTheFileWithIt(@TempDir Path folder) throws IOException {
		Path file = Files.writeString(folder.resolve("photo.jpg"), "bytes");

		doThrow(new IllegalStateException("the placement could not be written")).when(catalogFileLocationRepository)
				.saveAll(any());

		MetadataResult metadata = metadata(file);

		assertThatThrownBy(() -> inventoryPersistenceService.save(file, metadata, ANALYSE))
				.isInstanceOf(IllegalStateException.class);

		assertThat(cataloguedAt(file)).as("a file on record at no place").isZero();
		assertThat(placementsAt(file)).isZero();
	}

	/** And the same file catalogues normally once the door works again. */
	@Test
	void theSameFileIsCataloguedOnceTheWriteCanHappen(@TempDir Path folder) throws IOException {
		Path file = Files.writeString(folder.resolve("photo.jpg"), "bytes");

		doThrow(new IllegalStateException("the placement could not be written")).when(catalogFileLocationRepository)
				.saveAll(any());

		MetadataResult metadata = metadata(file);

		assertThatThrownBy(() -> inventoryPersistenceService.save(file, metadata, ANALYSE))
				.isInstanceOf(IllegalStateException.class);

		reset(catalogFileLocationRepository);

		inventoryPersistenceService.save(file, metadata(file), ANALYSE);

		assertThat(cataloguedAt(file)).isOne();
		assertThat(placementsAt(file)).isOne();
	}

	/**
	 * The shape of the batch, asserted where it can be: the placements are handed
	 * over in one call, never one at a time, and never flushed per file.
	 *
	 * <p>
	 * What this cannot assert is the count of statements the driver issued -
	 * counting those would mean an interceptor that exists only for the test, and
	 * the number it produced would describe the instrument as much as the code.
	 * What is asserted instead is the shape that decides it: one call carrying
	 * every placement, no per-file save, no per-file flush, and a row for each
	 * file keyed by the file itself.
	 */
	@Test
	void aBatchWritesEveryPlacementInOneCall(@TempDir Path folder) throws IOException {
		List<ScannedFile> scanned = List.of(scanned(folder, "a.jpg"), scanned(folder, "b.jpg"),
				scanned(folder, "c.jpg"));

		inventoryPersistenceService.saveOrCacheBatch(scanned, ANALYSE, this::metadata, context);

		verify(catalogFileLocationRepository, times(1)).saveAll(any());
		verify(catalogFileLocationRepository, never()).save(any());
		verify(catalogFileLocationRepository, never()).saveAndFlush(any());

		for (ScannedFile file : scanned) {
			assertThat(placementsAt(file.path())).as("one placement for %s", file.path()).isOne();
		}

		assertThat(placementsKeyedByTheirFile(folder)).as("every placement is identified by the file it places")
				.isEqualTo(scanned.size());
	}

	/**
	 * As the walk would hand it over, which means the instant at the precision the
	 * catalog keeps - a fixture carrying the filesystem's nanoseconds describes
	 * something the scanner never produces.
	 */
	private ScannedFile scanned(Path folder, String name) throws IOException {
		Path file = Files.writeString(folder.resolve(name), "bytes of " + name);

		return new ScannedFile(file, Files.size(file), CatalogTimestamp.observed(Files.getLastModifiedTime(file)));
	}

	private MetadataResult metadata(Path file) {
		return MetadataResult.builder().fileName(file.getFileName().toString()).extension("jpg").sizeBytes(5L)
				.fileType(FileType.PHOTO).subcategory(MediaSubcategory.CAMERA).modifiedAt(Instant.now()).build();
	}

	private int cataloguedAt(Path file) {
		return jdbcTemplate.queryForObject("""
				SELECT count(*) FROM catalog_file m
				 JOIN catalog_file_location l ON l.catalog_file_id = m.id
				 WHERE l.current_path = ?
				""", Integer.class, PathUtils.normalize(file));
	}

	private int placementsAt(Path file) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM catalog_file_location WHERE current_path = ?",
				Integer.class, PathUtils.normalize(file));
	}

	/**
	 * Every placement under the folder whose key really is the file it names -
	 * which, since the key became the file, is a count the schema itself keeps
	 * true and this only reads back.
	 */
	private int placementsKeyedByTheirFile(Path folder) {
		return jdbcTemplate.queryForObject("""
				SELECT count(*) FROM catalog_file_location l
				 JOIN catalog_file m ON m.id = l.catalog_file_id
				 WHERE l.current_folder = ?
				""", Integer.class, PathUtils.normalize(folder));
	}
}