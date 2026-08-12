package br.com.jorgemelo.nimbusfilemanager.metadata.application.date;

import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.concurrent.atomic.AtomicInteger;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.CatalogTimestamp;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.FileSystemDates;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.filename.FileNameDateRuleEngine;

@ExtendWith(MockitoExtension.class)
class DateSourceServiceTest {

	@TempDir
	Path tempDir;

	@Mock
	private FolderLayoutDateResolver folderLayoutDateResolver;

	@Mock
	private FileNameDateRuleEngine fileNameDateRuleEngine;

	@Test
	void resolveShouldValidateFileSystemModifiedFileNameAndFolderDates() throws Exception {
		Path file = Files.writeString(tempDir.resolve("IMG_20240509.jpg"), "content");

		LocalDateTime expected = LocalDateTime.of(2024, Month.MAY, 9, 10, 30);

		when(fileNameDateRuleEngine.resolve("IMG_20240509.jpg")).thenReturn(expected);
		when(folderLayoutDateResolver.resolve(file)).thenReturn(expected);

		DateSourceService service = service();

		Assertions.assertThat(service.resolveFileSystemDates(file)).isNotNull();
		Assertions.assertThat(service.resolveFromFileName(file)).isEqualTo(expected);
		Assertions.assertThat(service.resolveFromFolderLayout(file)).isEqualTo(expected);
	}

	@Test
	void resolveShouldDiscardDatesOutsideAcceptedRange() throws Exception {
		Path file = Files.writeString(tempDir.resolve("old.jpg"), "content");

		LocalDateTime old = LocalDateTime.of(1990, Month.JANUARY, 1, 0, 0);

		when(fileNameDateRuleEngine.resolve("old.jpg")).thenReturn(old);

		Assertions.assertThat(service().resolveFromFileName(file)).isNull();
	}

	@Test
	void resolveShouldReturnNullWhenFolderLayoutDateIsInvalid() throws Exception {
		Path file = Files.writeString(tempDir.resolve("photo.jpg"), "content");
		LocalDateTime future = LocalDateTime.now().plusYears(2);

		when(folderLayoutDateResolver.resolve(file)).thenReturn(future);

		Assertions.assertThat(service().resolveFromFolderLayout(file)).isNull();
	}

	@Test
	void resolveFileSystemDatesShouldReadCreatedAndModifiedFromASingleAttributesCall() throws Exception {
		Path file = Files.writeString(tempDir.resolve("photo.jpg"), "content");

		FileTime created = FileTime.fromMillis(1_600_000_000_000L);
		FileTime modified = FileTime.fromMillis(1_700_000_000_000L);

		AtomicInteger readAttributesCalls = new AtomicInteger();

		DateSourceService service = service(reader(created, modified, readAttributesCalls));

		FileSystemDates dates = service.resolveFileSystemDates(file);

		Assertions.assertThat(dates.createdAt()).isEqualTo(created.toInstant());
		Assertions.assertThat(dates.modifiedAt()).isEqualTo(modified.toInstant());
		Assertions.assertThat(readAttributesCalls).hasValue(1);
	}

	/**
	 * Both dates leave here at the precision the catalog stores, and this is where
	 * that has to hold: they become {@code CatalogFile.createdAt} and
	 * {@code modifiedAt}, and a value finer than the column comes back different
	 * from what was written - which every later comparison reads as a file that
	 * changed. A filesystem reports finer than this: NTFS counts in hundreds of
	 * nanoseconds.
	 */
	@Test
	void resolveFileSystemDatesShouldAnswerAtThePrecisionTheCatalogStores() throws Exception {
		Path file = Files.writeString(tempDir.resolve("photo.jpg"), "content");

		FileTime created = FileTime.from(Instant.ofEpochSecond(1_600_000_000L, 123_456_789));
		FileTime modified = FileTime.from(Instant.ofEpochSecond(1_700_000_000L, 950_657_845));

		DateSourceService service = service(reader(created, modified, new AtomicInteger()));

		FileSystemDates dates = service.resolveFileSystemDates(file);

		Assertions.assertThat(dates.createdAt()).isEqualTo(Instant.ofEpochSecond(1_600_000_000L, 123_456_000));
		Assertions.assertThat(dates.modifiedAt()).isEqualTo(Instant.ofEpochSecond(1_700_000_000L, 950_657_000));

		Assertions.assertThat(dates.createdAt()).isEqualTo(CatalogTimestamp.observed(created));
		Assertions.assertThat(dates.modifiedAt()).isEqualTo(CatalogTimestamp.observed(modified));
	}

	/** No creation time is the ordinary case on some filesystems, not a failure. */
	@Test
	void resolveFileSystemDatesShouldFallbackToLastModifiedWhenCreationTimeIsMissing() throws Exception {
		Path file = Files.writeString(tempDir.resolve("photo.jpg"), "content");

		FileTime modified = FileTime.fromMillis(1_700_000_000_000L);

		DateSourceService service = service(reader(null, modified, new AtomicInteger()));

		FileSystemDates dates = service.resolveFileSystemDates(file);

		Assertions.assertThat(dates.createdAt()).isEqualTo(modified.toInstant());
	}

	@Test
	void resolveFileSystemDatesShouldWrapReadFailures() throws Exception {
		Path file = Files.writeString(tempDir.resolve("photo.jpg"), "content");

		DateSourceService service = service(_ -> {
			throw new IOException("attributes failed");
		});

		Assertions.assertThatThrownBy(() -> service.resolveFileSystemDates(file))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("Could not read file dates");
	}

	/**
	 * A reader answering exactly the two instants under test, counting the reads.
	 * A null creation time is what a filesystem that does not keep one reports.
	 */
	private FileDateReader reader(FileTime created, FileTime modified, AtomicInteger reads) {
		return _ -> {
			reads.incrementAndGet();

			return attributes(created, modified);
		};
	}

	private BasicFileAttributes attributes(FileTime created, FileTime modified) {
		return new BasicFileAttributes() {

			@Override
			public FileTime lastModifiedTime() {
				return modified;
			}

			@Override
			public FileTime lastAccessTime() {
				return modified;
			}

			@Override
			public FileTime creationTime() {
				return created;
			}

			@Override
			public boolean isRegularFile() {
				return true;
			}

			@Override
			public boolean isDirectory() {
				return false;
			}

			@Override
			public boolean isSymbolicLink() {
				return false;
			}

			@Override
			public boolean isOther() {
				return false;
			}

			@Override
			public long size() {
				return 1L;
			}

			@Override
			public Object fileKey() {
				return null;
			}
		};
	}

	private DateSourceService service() {
		return new DateSourceService(folderLayoutDateResolver, fileNameDateRuleEngine,
				new CaptureDateValidator(Clock.systemDefaultZone()), Clock.systemDefaultZone());
	}

	private DateSourceService service(FileDateReader fileDateReader) {
		return new DateSourceService(folderLayoutDateResolver, fileNameDateRuleEngine,
				new CaptureDateValidator(Clock.systemDefaultZone()), fileDateReader, Clock.systemDefaultZone());
	}
}