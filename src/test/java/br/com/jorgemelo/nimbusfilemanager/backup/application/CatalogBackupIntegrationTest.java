package br.com.jorgemelo.nimbusfilemanager.backup.application;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.BackupFile;
import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.DatabaseConnection;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

/**
 * Backup and restore against a real PostgreSQL, because that is the only place
 * they exist: {@code COPY}, {@code TRUNCATE ... CASCADE} and the sequence
 * realignment are the database doing the work, and a mock would prove nothing
 * about any of them.
 *
 * <p>
 * The scenario is the one the feature is for: something is catalogued, a backup
 * is taken, the catalog is then lost - and the restore has to bring it back
 * exactly, ids included, with the sequences able to keep issuing new ones.
 */
@SpringBootTest
@Testcontainers
class CatalogBackupIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	@Autowired
	private CatalogDump catalogDump;

	@Autowired
	private CatalogBackupService catalogBackupService;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private AppSettingService appSettingService;

	@TempDir
	Path backupFolder;

	@BeforeEach
	void useATemporaryBackupFolder() {
		appSettingService.update(SettingsConstants.BACKUP_FOLDER, backupFolder.toString(), "system");

		catalogFileRepository.deleteAll();
	}

	private CatalogFile catalogued(String fileKey) {
		return catalogFileRepository.save(CatalogFile.builder().fileKey(fileKey).fileName("photo.jpg").extension("jpg")
				.sizeBytes(1024L).sha256(fileKey).fileType(FileType.PHOTO).lifecycleStatus(LifecycleStatus.ACTIVE)
				.modifiedAt(LocalDateTime.now()).importedAt(LocalDateTime.now()).build());
	}

	/**
	 * The dump and the restore act on a database chosen at runtime, and the
	 * restore destroys what it replaces. This pins the target to the one the
	 * application is actually connected to.
	 *
	 * <p>
	 * It exists because the first version read {@code spring.datasource.url}
	 * instead. Under {@code @ServiceConnection} that property keeps the packaged
	 * default, so the suite dumped and restored a developer machine's own
	 * database while every assertion here went on passing or failing for
	 * unrelated reasons.
	 */
	@Test
	void actsOnTheDatabaseTheApplicationIsConnectedTo() {
		DatabaseConnection target = catalogDump.target();

		Assertions.assertThat(target.port()).isEqualTo(postgres.getFirstMappedPort());
		Assertions.assertThat(target.database()).isEqualTo(postgres.getDatabaseName());
	}

	@Test
	void bringsBackACatalogThatWasLost() {
		Long id = catalogued("D:\\Media\\one.jpg").getId();

		BackupFile backup = catalogBackupService.create();

		catalogFileRepository.deleteAll();

		Assertions.assertThat(catalogFileRepository.count()).isZero();

		catalogBackupService.restore(backup.name());

		Assertions.assertThat(catalogFileRepository.count()).isEqualTo(1);
		Assertions.assertThat(catalogFileRepository.findById(id)).isPresent();
	}

	/**
	 * The sequences are restarted by the truncate; without realigning them the next
	 * insert after a restore collides with a restored id, which would turn a
	 * successful rescue into a broken catalog on the very next file.
	 */
	@Test
	void leavesTheCatalogAbleToTakeNewFilesAfterARestore() {
		catalogued("D:\\Media\\one.jpg");
		catalogued("D:\\Media\\two.jpg");

		BackupFile backup = catalogBackupService.create();

		catalogFileRepository.deleteAll();

		catalogBackupService.restore(backup.name());

		Assertions.assertThat(catalogued("D:\\Media\\three.jpg").getId()).isNotNull();
		Assertions.assertThat(catalogFileRepository.count()).isEqualTo(3);
	}

	@Test
	void listsWhatWasTakenNewestFirst() {
		catalogued("D:\\Media\\one.jpg");

		BackupFile first = catalogBackupService.create();

		List<BackupFile> backups = catalogBackupService.list();

		Assertions.assertThat(backups).extracting(BackupFile::name).contains(first.name());
		Assertions.assertThat(backups.get(0).sizeBytes()).isPositive();
	}

	@Test
	void deletesABackupThatIsNoLongerWanted() {
		BackupFile backup = catalogBackupService.create();

		catalogBackupService.delete(backup.name());

		Assertions.assertThat(catalogBackupService.list()).extracting(BackupFile::name).doesNotContain(backup.name());
	}

	/**
	 * A name that is not a backup of this installation - or one carrying a path -
	 * must not be read or deleted. The screen only ever sends names it listed, so
	 * anything else arrived by hand.
	 */
	@Test
	void refusesANameThatIsNotOneOfItsBackups() {
		Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> catalogBackupService.restore("../../etc/passwd"));
		Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> catalogBackupService.restore("holiday-photos.zip"));
	}
}