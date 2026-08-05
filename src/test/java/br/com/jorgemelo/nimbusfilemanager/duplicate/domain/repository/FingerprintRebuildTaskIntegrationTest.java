package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.FingerprintAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.FingerprintRebuildTask;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

/**
 * The table that will hold what a rebuild still owes, before anything asks it.
 *
 * <p>
 * Nothing is wired to it yet on purpose: what is proved here is the shape the
 * rest of the slice depends on. Two of those properties are the reason the key
 * is what it is - a target cannot end up with two lists, and a file can owe more
 * than one target at a time - and the third is the whole of the cleanup story:
 * a file that leaves the catalog takes its debt with it, so no sweeper has to
 * decide whether an open list is rubbish or work.
 *
 * <p>
 * The last two tests are about what this migration did <em>not</em> do. The
 * fingerprints stay exactly where they were, under the same unique key, because
 * this table takes over the ledger role without touching the data or a single
 * query that reads it.
 */
class FingerprintRebuildTaskIntegrationTest extends SharedPostgresIntegrationTest {

	private static final String PHOTO_ALGORITHM = FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1;

	@Autowired
	private FingerprintRebuildTaskRepository taskRepository;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * Asking for the same rebuild twice tops the list back up; it does not build a
	 * second one beside it.
	 */
	@Test
	void owingTheSameFileTwiceIsStillOneDebt() {
		long fileId = catalogued();

		taskRepository.saveAndFlush(owed(fileId, FingerprintKind.PHOTO_PHASH, PHOTO_ALGORITHM));
		taskRepository.saveAndFlush(owed(fileId, FingerprintKind.PHOTO_PHASH, PHOTO_ALGORITHM));

		assertThat(taskRepository.countByKindAndAlgorithm(FingerprintKind.PHOTO_PHASH, PHOTO_ALGORITHM))
				.as("one task per file per target, however many times it is asked for").isEqualTo(1);
	}

	/**
	 * And the database says so itself, rather than relying on the mapping to have
	 * merged: the seed will insert straight through JPA, so the key has to be the
	 * thing that refuses.
	 */
	@Test
	void theDatabaseRefusesASecondRowForTheSameTarget() {
		long fileId = catalogued();

		insertDirectly(fileId, FingerprintKind.PHOTO_PHASH, PHOTO_ALGORITHM);

		assertThatThrownBy(() -> insertDirectly(fileId, FingerprintKind.PHOTO_PHASH, PHOTO_ALGORITHM))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	/**
	 * A photo rebuild and a video rebuild are different debts of the same catalog
	 * row - a still exported beside a clip shares an id with it and nothing else -
	 * so the file may owe both at once, and finishing one leaves the other.
	 */
	@Test
	void aFileMayOweMoreThanOneTargetAtATime() {
		long fileId = catalogued();

		taskRepository.saveAndFlush(owed(fileId, FingerprintKind.PHOTO_PHASH, PHOTO_ALGORITHM));
		taskRepository.saveAndFlush(owed(fileId, FingerprintKind.VIDEO_PHASH,
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_FRAMES_V1));

		assertThat(taskRepository.count()).isEqualTo(2);

		assertThat(taskRepository.consume(FingerprintKind.PHOTO_PHASH, PHOTO_ALGORITHM, fileId)).isEqualTo(1);

		assertThat(taskRepository.existsByKindAndAlgorithm(FingerprintKind.PHOTO_PHASH, PHOTO_ALGORITHM))
				.as("the target that was paid is finished").isFalse();
		assertThat(taskRepository.existsByKindAndAlgorithm(FingerprintKind.VIDEO_PHASH,
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_FRAMES_V1)).as("the other one still owes").isTrue();
	}

	/**
	 * The whole of the cleanup. A list left open is outstanding work rather than
	 * rubbish, so nothing sweeps it; what does have to go is the debt of a file
	 * that is no longer catalogued, and the foreign key does that without anybody
	 * scheduling it.
	 */
	@Test
	void aFileThatLeavesTheCatalogTakesItsDebtWithIt() {
		long fileId = catalogued();

		taskRepository.saveAndFlush(owed(fileId, FingerprintKind.PHOTO_PHASH, PHOTO_ALGORITHM));

		catalogFileRepository.deleteById(fileId);
		catalogFileRepository.flush();

		assertThat(taskRepository.countByKindAndAlgorithm(FingerprintKind.PHOTO_PHASH, PHOTO_ALGORITHM)).isZero();
	}

	/** Consuming a debt nobody owes is no error, and says it changed nothing. */
	@Test
	void consumingATaskThatIsNotThereChangesNothing() {
		assertThat(taskRepository.consume(FingerprintKind.PHOTO_PHASH, PHOTO_ALGORITHM, 4242L)).isZero();
	}

	/**
	 * The fingerprints keep the key they had. Relaxing it is what a generation
	 * column would have needed, and the point of taking the ledger out instead is
	 * that neither the data nor the constraint over it moves.
	 */
	@Test
	void theFingerprintUniqueKeyIsUntouched() {
		assertThat(constraintOf("uk_media_fingerprint"))
				.isEqualTo("UNIQUE (catalog_file_id, kind, algorithm, sample_index)");
	}

	/**
	 * And it kept its columns, which is the observable form of "no consumer query
	 * had to change": every query that reads a fingerprint reads these and nothing
	 * else.
	 */
	@Test
	void theFingerprintTableKeptItsColumns() {
		assertThat(columnsOf("media_fingerprint")).containsExactly("id", "catalog_file_id", "kind", "algorithm",
				"sample_index", "position_ms", "hash", "hash_bytes", "sample_bytes", "computed_at");
	}

	private FingerprintRebuildTask owed(long catalogFileId, FingerprintKind kind, String algorithm) {
		return FingerprintRebuildTask.builder().kind(kind).algorithm(algorithm).catalogFileId(catalogFileId)
				.seededAt(LocalDateTime.now()).build();
	}

	private void insertDirectly(long catalogFileId, FingerprintKind kind, String algorithm) {
		jdbcTemplate.update("""
				INSERT INTO fingerprint_rebuild_task (kind, algorithm, catalog_file_id, seeded_at)
				VALUES (?, ?, ?, now())
				""", kind.name(), algorithm, catalogFileId);
	}

	private String constraintOf(String name) {
		return jdbcTemplate.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?",
				String.class, name);
	}

	private List<String> columnsOf(String table) {
		return jdbcTemplate.queryForList(
				"SELECT column_name FROM information_schema.columns WHERE table_name = ? ORDER BY ordinal_position",
				String.class, table);
	}

	private long catalogued() {
		String key = "rebuild-task-" + System.nanoTime();

		String path = "C:/test/" + key + ".jpg";

		CatalogFile file = CatalogFile.builder().fileKey(key).fileName(key + ".jpg").extension("jpg").sizeBytes(1L)
				.modifiedAt(LocalDateTime.now()).fileType(FileType.PHOTO).lifecycleStatus(LifecycleStatus.ACTIVE)
				.build();
		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(path).currentFolder("C:/test")
				.originalPath(path).originalFolder("C:/test").build());

		return catalogFileRepository.saveAndFlush(file).getId();
	}
}