package br.com.jorgemelo.nimbusfilemanager.metadata.application.extractor;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Month;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.MediaOrientationResolver;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.MimeTypeService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.classifier.MediaSubcategoryResolver;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.date.CaptureDateRefiner;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.date.DateSourceService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.FileHashes;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.FileSystemDates;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataOptions;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.PhotoMetadata;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoMetadata;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.model.MetadataResult;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MediaOrientation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;

@ExtendWith(MockitoExtension.class)
class MetadataExtractorTest {

	@TempDir
	Path tempDir;

	@Mock
	private MimeTypeService mimeTypeService;

	@Mock
	private FileHashService fileHashService;

	@Mock
	private DateSourceService dateSourceService;

	@Mock
	private MediaMetadataReader mediaMetadataReader;

	@Mock
	private MediaSubcategoryResolver mediaSubcategoryResolver;

	@Test
	void extractPhotoShouldPreferExifDateAndCalculateHashes() throws Exception {
		Path file = Files.writeString(tempDir.resolve("photo.JPG"), "photo-content");

		LocalDateTime createdAt = LocalDateTime.of(2024, Month.JANUARY, 1, 8, 0);
		LocalDateTime modifiedAt = LocalDateTime.of(2024, Month.JANUARY, 2, 8, 0);
		LocalDateTime captureDate = LocalDateTime.of(2024, Month.JANUARY, 3, 9, 30);

		PhotoMetadata photo = new PhotoMetadata(3000, 4000, "Canon", "R6", 6, -23.5, -46.6, 100, "No Flash", "1/100",
				"2.8", "50mm", "RF", "Auto", "Auto", "Program", "Pattern", captureDate, "{exif}");

		when(mimeTypeService.detect(file)).thenReturn("image/jpeg");
		when(dateSourceService.resolveFileSystemDates(file)).thenReturn(new FileSystemDates(createdAt, modifiedAt));
		when(fileHashService.hashes(file)).thenReturn(new FileHashes("sha256", "md5"));
		when(mediaMetadataReader.photo(file)).thenReturn(photo);
		when(dateSourceService.resolveFromFileName(file)).thenReturn(LocalDateTime.of(2024, Month.JANUARY, 4, 0, 0));
		when(mediaSubcategoryResolver.resolve(file)).thenReturn(MediaSubcategory.CAMERA);

		MetadataResult result = extractor().extract(file, new MetadataOptions(true, false));

		Assertions.assertThat(result.getFileName()).isEqualTo("photo.JPG");
		Assertions.assertThat(result.getExtension()).isEqualTo("jpg");
		Assertions.assertThat(result.getSizeBytes()).isEqualTo(Files.size(file));
		Assertions.assertThat(result.getSha256()).isEqualTo("sha256");
		Assertions.assertThat(result.getMd5()).isEqualTo("md5");
		Assertions.assertThat(result.getFileType()).isEqualTo(FileType.PHOTO);
		Assertions.assertThat(result.getCaptureDate()).isEqualTo(captureDate);
		Assertions.assertThat(result.getDateSource()).isEqualTo(DateSource.EXIF);
		Assertions.assertThat(result.getDisplayWidth()).isEqualTo(4000);
		Assertions.assertThat(result.getDisplayHeight()).isEqualTo(3000);
		Assertions.assertThat(result.getRotation()).isEqualTo(90);
		Assertions.assertThat(result.getOrientationType()).isEqualTo(MediaOrientation.LANDSCAPE);
		Assertions.assertThat(result.getManufacturer()).isEqualTo("Canon");
		Assertions.assertThat(result.getLatitude()).isEqualTo(-23.5);
		Assertions.assertThat(result.getIso()).isEqualTo(100);
		Assertions.assertThat(result.getExifJson()).isEqualTo("{exif}");

		verify(mediaMetadataReader, never()).video(file);
	}

	@Test
	void extractVideoShouldPreferMediaInfoDateAndNormalizeNegativeRotation() throws Exception {
		Path file = Files.writeString(tempDir.resolve("video.mp4"), "video-content");

		LocalDateTime createdAt = LocalDateTime.of(2024, Month.JANUARY, 1, 8, 0);
		LocalDateTime modifiedAt = LocalDateTime.of(2024, Month.JANUARY, 2, 8, 0);
		LocalDateTime captureDate = LocalDateTime.of(2024, Month.JANUARY, 3, 9, 30);

		VideoMetadata video = new VideoMetadata("mov", "h265", "aac", "main", 1920, 1080, 59.94, 1000L, 1200L, 10.5,
				-90, true, "yuv420p", "bt2020", "smpte2084", "bt2020", 10, 48000, 2, "stereo", captureDate, -23.5,
				-46.6, "{media}", MediaOrientation.LANDSCAPE);

		when(mimeTypeService.detect(file)).thenReturn("video/mp4");
		when(dateSourceService.resolveFileSystemDates(file)).thenReturn(new FileSystemDates(createdAt, modifiedAt));
		when(mediaMetadataReader.video(file)).thenReturn(video);
		when(mediaSubcategoryResolver.resolve(file)).thenReturn(MediaSubcategory.GOPRO);

		MetadataResult result = extractor().extract(file, null);

		Assertions.assertThat(result.getFileType()).isEqualTo(FileType.VIDEO);
		Assertions.assertThat(result.getCaptureDate()).isEqualTo(captureDate);
		Assertions.assertThat(result.getDateSource()).isEqualTo(DateSource.MEDIA_INFO);
		Assertions.assertThat(result.getRotation()).isEqualTo(270);
		Assertions.assertThat(result.getDisplayWidth()).isEqualTo(1080);
		Assertions.assertThat(result.getDisplayHeight()).isEqualTo(1920);
		Assertions.assertThat(result.getOrientationType()).isEqualTo(MediaOrientation.PORTRAIT);
		Assertions.assertThat(result.getContainer()).isEqualTo("mov");
		Assertions.assertThat(result.getHdr()).isTrue();
		Assertions.assertThat(result.getMediaInfoJson()).isEqualTo("{media}");

		verify(fileHashService, never()).hashes(file);
		verify(mediaMetadataReader, never()).photo(file);
	}

	@Test
	void extractDocumentShouldUseFileNameDateWhenMediaDatesAreMissing() throws Exception {
		Path file = Files.writeString(tempDir.resolve("document.pdf"), "pdf");

		LocalDateTime createdAt = LocalDateTime.of(2024, Month.JANUARY, 1, 8, 0);
		LocalDateTime fileNameDate = LocalDateTime.of(2024, Month.FEBRUARY, 2, 0, 0);

		when(mimeTypeService.detect(file)).thenReturn("application/pdf");
		when(dateSourceService.resolveFileSystemDates(file)).thenReturn(new FileSystemDates(createdAt, createdAt));
		when(dateSourceService.resolveFromFileName(file)).thenReturn(fileNameDate);
		when(mediaSubcategoryResolver.resolve(file)).thenReturn(MediaSubcategory.UNKNOWN);

		MetadataResult result = extractor().extract(file, new MetadataOptions(false, false));

		Assertions.assertThat(result.getFileType()).isEqualTo(FileType.PDF);
		Assertions.assertThat(result.getCaptureDate()).isEqualTo(fileNameDate);
		Assertions.assertThat(result.getDateSource()).isEqualTo(DateSource.FILE_NAME);
		Assertions.assertThat(result.getOrientationType()).isEqualTo(MediaOrientation.UNKNOWN);
		Assertions.assertThat(result.getStoredWidth()).isNull();
	}

	@Test
	void extractShouldRefineMidnightFileNameDateWhenFilesystemCorroboratesTheDay() throws Exception {
		Path file = Files.writeString(tempDir.resolve("AUD-20240202-WA0001.pdf"), "pdf");

		LocalDateTime nameDay = LocalDateTime.of(2024, Month.FEBRUARY, 2, 0, 0);
		LocalDateTime created = LocalDateTime.of(2024, Month.FEBRUARY, 2, 23, 0);
		LocalDateTime modified = LocalDateTime.of(2024, Month.FEBRUARY, 2, 14, 36, 14);

		when(mimeTypeService.detect(file)).thenReturn("application/pdf");
		when(dateSourceService.resolveFileSystemDates(file)).thenReturn(new FileSystemDates(created, modified));
		when(dateSourceService.resolveFromFileName(file)).thenReturn(nameDay);
		when(mediaSubcategoryResolver.resolve(file)).thenReturn(MediaSubcategory.WHATSAPP);

		MetadataResult result = extractor().extract(file, new MetadataOptions(false, false));

		// midnight name date gains the real time-of-day (modified preferred), source
		// upgraded
		Assertions.assertThat(result.getCaptureDate()).isEqualTo(modified);
		Assertions.assertThat(result.getDateSource()).isEqualTo(DateSource.FILE_NAME_CONFIRMED);
	}

	@Test
	void extractDocumentShouldUseFolderLayoutThenCreatedAtAsDateFallbacks() throws Exception {
		Path folderFile = Files.writeString(tempDir.resolve("folder-date.pdf"), "pdf");
		Path createdFile = Files.writeString(tempDir.resolve("created-date.pdf"), "pdf");

		LocalDateTime folderCreatedAt = LocalDateTime.of(2024, Month.JANUARY, 1, 8, 0);
		LocalDateTime folderDate = LocalDateTime.of(2024, Month.MARCH, 3, 0, 0);
		LocalDateTime createdAt = LocalDateTime.of(2024, Month.APRIL, 4, 8, 0);

		when(mimeTypeService.detect(folderFile)).thenReturn("application/pdf");
		when(dateSourceService.resolveFileSystemDates(folderFile))
				.thenReturn(new FileSystemDates(folderCreatedAt, folderCreatedAt));
		when(dateSourceService.resolveFromFileName(folderFile)).thenReturn(null);
		when(dateSourceService.resolveFromFolderLayout(folderFile)).thenReturn(folderDate);
		when(mediaSubcategoryResolver.resolve(folderFile)).thenReturn(MediaSubcategory.UNKNOWN);
		when(mimeTypeService.detect(createdFile)).thenReturn("application/pdf");
		when(dateSourceService.resolveFileSystemDates(createdFile))
				.thenReturn(new FileSystemDates(createdAt, createdAt));
		when(dateSourceService.resolveFromFileName(createdFile)).thenReturn(null);
		when(dateSourceService.resolveFromFolderLayout(createdFile)).thenReturn(null);
		when(mediaSubcategoryResolver.resolve(createdFile)).thenReturn(MediaSubcategory.UNKNOWN);

		MetadataResult folderResult = extractor().extract(folderFile, new MetadataOptions(false, false));
		MetadataResult createdResult = extractor().extract(createdFile, new MetadataOptions(false, false));

		Assertions.assertThat(folderResult.getCaptureDate()).isEqualTo(folderDate);
		Assertions.assertThat(folderResult.getDateSource()).isEqualTo(DateSource.FOLDER_LAYOUT);
		Assertions.assertThat(createdResult.getCaptureDate()).isEqualTo(createdAt);
		Assertions.assertThat(createdResult.getDateSource()).isEqualTo(DateSource.FILE_CREATED_AT);
	}

	@Test
	void extractShouldPreferOlderModifiedOverCreatedInFilesystemFallback() throws Exception {
		Path file = Files.writeString(tempDir.resolve("no-date.pdf"), "pdf");

		LocalDateTime created = LocalDateTime.of(2024, Month.JULY, 7, 17, 58); // copy/sync date (newer)
		LocalDateTime modified = LocalDateTime.of(2019, Month.APRIL, 14, 9, 0); // preserved original mtime (older)

		when(mimeTypeService.detect(file)).thenReturn("application/pdf");
		when(dateSourceService.resolveFileSystemDates(file)).thenReturn(new FileSystemDates(created, modified));
		when(dateSourceService.resolveFromFileName(file)).thenReturn(null);
		when(dateSourceService.resolveFromFolderLayout(file)).thenReturn(null);
		when(mediaSubcategoryResolver.resolve(file)).thenReturn(MediaSubcategory.UNKNOWN);

		MetadataResult result = extractor().extract(file, new MetadataOptions(false, false));

		// oldest filesystem timestamp wins, and FILE_MODIFIED_AT is finally used
		Assertions.assertThat(result.getCaptureDate()).isEqualTo(modified);
		Assertions.assertThat(result.getDateSource()).isEqualTo(DateSource.FILE_MODIFIED_AT);
	}

	@Test
	void extractPhotoShouldMapOrientationBoundaries() throws Exception {
		Path rotated = Files.writeString(tempDir.resolve("rotated.jpg"), "photo");
		Path square = Files.writeString(tempDir.resolve("square.jpg"), "photo");

		LocalDateTime createdAt = LocalDateTime.of(2024, Month.JANUARY, 1, 8, 0);

		PhotoMetadata orientationEight = new PhotoMetadata(4000, 3000, null, null, 8, null, null, null, null, null,
				null, null, null, null, null, null, null, null, null);
		PhotoMetadata orientationDefault = new PhotoMetadata(1000, 1000, null, null, 1, null, null, null, null, null,
				null, null, null, null, null, null, null, null, null);

		when(mimeTypeService.detect(rotated)).thenReturn("image/jpeg");
		when(dateSourceService.resolveFileSystemDates(rotated)).thenReturn(new FileSystemDates(createdAt, createdAt));
		when(mediaMetadataReader.photo(rotated)).thenReturn(orientationEight);
		when(dateSourceService.resolveFromFileName(rotated)).thenReturn(null);
		when(dateSourceService.resolveFromFolderLayout(rotated)).thenReturn(null);
		when(mediaSubcategoryResolver.resolve(rotated)).thenReturn(MediaSubcategory.CAMERA);
		when(mimeTypeService.detect(square)).thenReturn("image/jpeg");
		when(dateSourceService.resolveFileSystemDates(square)).thenReturn(new FileSystemDates(createdAt, createdAt));
		when(mediaMetadataReader.photo(square)).thenReturn(orientationDefault);
		when(dateSourceService.resolveFromFileName(square)).thenReturn(null);
		when(dateSourceService.resolveFromFolderLayout(square)).thenReturn(null);
		when(mediaSubcategoryResolver.resolve(square)).thenReturn(MediaSubcategory.CAMERA);

		MetadataResult rotatedResult = extractor().extract(rotated, new MetadataOptions(false, false));
		MetadataResult squareResult = extractor().extract(square, new MetadataOptions(false, false));

		Assertions.assertThat(rotatedResult.getRotation()).isEqualTo(270);
		Assertions.assertThat(rotatedResult.getDisplayWidth()).isEqualTo(3000);
		Assertions.assertThat(rotatedResult.getDisplayHeight()).isEqualTo(4000);
		Assertions.assertThat(rotatedResult.getOrientationType()).isEqualTo(MediaOrientation.PORTRAIT);
		Assertions.assertThat(squareResult.getRotation()).isZero();
		Assertions.assertThat(squareResult.getOrientationType()).isEqualTo(MediaOrientation.SQUARE);
	}

	@Test
	void extractShouldWrapFileSizeErrors() {
		Path missing = tempDir.resolve("missing.jpg");

		when(mimeTypeService.detect(missing)).thenReturn("image/jpeg");
		when(dateSourceService.resolveFileSystemDates(missing))
				.thenReturn(new FileSystemDates(LocalDateTime.now(), LocalDateTime.now()));
		when(mediaMetadataReader.photo(missing)).thenReturn(null);
		when(mediaSubcategoryResolver.resolve(missing)).thenReturn(MediaSubcategory.UNKNOWN);

		assertThatIllegalStateException().isThrownBy(() -> extractor().extract(missing, null))
				.withMessageContaining("Could not read file size");
	}

	@Test
	void nonMediaExtensionWithEmbeddedImageMimeIsNotTreatedAsPhoto() throws Exception {
		// A Delphi .dfm form embeds image bytes, so content sniffing can report an
		// image MIME. The known (non-image) extension must win, and no perceptual hash
		// (ffmpeg) nor EXIF read may be attempted.
		Path file = Files.writeString(tempDir.resolve("ScreenHistoStretchGrays.dfm"), "object Screen: TForm");

		LocalDateTime now = LocalDateTime.of(2024, Month.JANUARY, 1, 8, 0);

		when(mimeTypeService.detect(file)).thenReturn("image/png");
		when(dateSourceService.resolveFileSystemDates(file)).thenReturn(new FileSystemDates(now, now));
		when(mediaSubcategoryResolver.resolve(file)).thenReturn(MediaSubcategory.UNKNOWN);

		MetadataResult result = extractor().extract(file, new MetadataOptions(false, false));

		Assertions.assertThat(result.getFileType()).isEqualTo(FileType.OTHER);

		verify(mediaMetadataReader, never()).photo(file);
	}

	@Test
	void archiveMasqueradingAsWebpRemainsCatalogedAsOtherWithoutHashesOrMediaExtraction() throws Exception {
		Path file = Files.write(tempDir.resolve("whatsapp-sticker.webp"), new byte[] { 'P', 'K', 3, 4 });

		LocalDateTime now = LocalDateTime.of(2024, Month.JANUARY, 1, 8, 0);

		when(mimeTypeService.detect(file)).thenReturn("application/zip");
		when(dateSourceService.resolveFileSystemDates(file)).thenReturn(new FileSystemDates(now, now));
		when(mediaSubcategoryResolver.resolve(file)).thenReturn(MediaSubcategory.WHATSAPP);

		MetadataResult result = extractor().extract(file, new MetadataOptions(true, false));

		Assertions.assertThat(result.getFileType()).isEqualTo(FileType.OTHER);
		Assertions.assertThat(result.getMimeType()).isEqualTo("application/zip");
		Assertions.assertThat(result.getSha256()).isNull();
		Assertions.assertThat(result.getMd5()).isNull();

		verify(fileHashService, never()).hashes(file);
		verify(mediaMetadataReader, never()).photo(file);
		verify(mediaMetadataReader, never()).video(file);
	}

	private MetadataExtractor extractor() {
		return new MetadataExtractor(mimeTypeService, fileHashService, dateSourceService, new CaptureDateRefiner(),
				mediaMetadataReader, mediaSubcategoryResolver, new MediaOrientationResolver());
	}
}