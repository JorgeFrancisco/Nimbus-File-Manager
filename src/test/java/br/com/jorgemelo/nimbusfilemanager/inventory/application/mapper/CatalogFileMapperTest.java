package br.com.jorgemelo.nimbusfilemanager.inventory.application.mapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Month;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.date.CaptureDateValidator;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.date.MediaDateResolver;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.model.MetadataResult;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaOrientation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Photo;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Video;

class CatalogFileMapperTest {

	/**
	 * Real resolver: it is a pure function of the extracted metadata, so stubbing
	 * it would only assert that the mapper copies back what the stub returned.
	 */
	private final MediaDateResolver mediaDateResolver = new MediaDateResolver(
			new CaptureDateValidator(Clock.systemDefaultZone()));

	@Test
	void toEntityShouldRejectAPathWithoutParentDirectory() {
		MetadataResult metadata = photoMetadata(LocalDateTime.of(2024, Month.MAY, 9, 10, 30));
		CatalogFileMapper mapper = new CatalogFileMapper(mediaDateResolver, Clock.systemDefaultZone());

		Path root = Path.of("/");

		Assertions.assertThatThrownBy(() -> mapper.toEntity(root, root, metadata))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("parent directory");
	}

	@Test
	void toEntityShouldMapPhotoMetadata() {
		LocalDateTime captureDate = LocalDateTime.of(2024, Month.MAY, 9, 10, 30);

		MetadataResult metadata = photoMetadata(captureDate);

		CatalogFile catalogFile = new CatalogFileMapper(mediaDateResolver, Clock.systemDefaultZone())
				.toEntity(Path.of("C:/input/photo.jpg"), Path.of("C:/input"), metadata);

		Assertions.assertThat(catalogFile.getFileName()).isEqualTo("photo.jpg");
		Assertions.assertThat(catalogFile.getFileType()).isEqualTo(FileType.PHOTO);
		Assertions.assertThat(catalogFile.isActive()).isTrue();
		Assertions.assertThat(catalogFile.getLocation()).isNotNull();
		Assertions.assertThat(catalogFile.getMetadata().getCategory()).isEqualTo(FileCategory.MEDIA);
		Assertions.assertThat(catalogFile.getMetadata().getSubcategory()).isEqualTo(MediaSubcategory.CAMERA);
		Assertions.assertThat(catalogFile.getMetadata().getYearMonth()).isEqualTo("2024-05");
		Assertions.assertThat(catalogFile.getPhoto()).isNotNull();
		Assertions.assertThat(catalogFile.getPhoto().getFormat()).isEqualTo("jpg");
		Assertions.assertThat(catalogFile.getPhoto().getIso()).isEqualTo(100);
		Assertions.assertThat(catalogFile.getVideo()).isNull();
	}

	@Test
	void updateEntityShouldReplaceExistingMediaBranches() {
		LocalDateTime captureDate = LocalDateTime.of(2024, Month.MAY, 9, 10, 30);

		MetadataResult metadata = videoMetadata(captureDate);

		CatalogFile catalogFile = CatalogFile.builder().fileType(FileType.PHOTO).build();

		new CatalogFileMapper(mediaDateResolver, Clock.systemDefaultZone()).updateEntity(catalogFile,
				Path.of("C:/input/video.mp4"), Path.of("C:/input"), metadata);

		Assertions.assertThat(catalogFile.getFileName()).isEqualTo("video.mp4");
		Assertions.assertThat(catalogFile.getFileType()).isEqualTo(FileType.VIDEO);
		Assertions.assertThat(catalogFile.getMetadata().getDateSource()).isEqualTo(DateSource.MEDIA_INFO);
		Assertions.assertThat(catalogFile.getLocation()).isNotNull();
		Assertions.assertThat(catalogFile.getPhoto()).isNull();
		Assertions.assertThat(catalogFile.getVideo()).isNotNull();

		assertVideoMetadata(catalogFile.getVideo());
	}

	@Test
	void toEntityShouldMapVideoMetadataAndUpdateExistingLocation() {
		LocalDateTime captureDate = LocalDateTime.of(2024, Month.MAY, 9, 10, 30);

		MetadataResult metadata = videoMetadata(captureDate);

		CatalogFile catalogFile = new CatalogFileMapper(mediaDateResolver, Clock.systemDefaultZone())
				.toEntity(Path.of("C:/input/video.mp4"), Path.of("C:/input"), metadata);

		Assertions.assertThat(catalogFile.getPhoto()).isNull();
		Assertions.assertThat(catalogFile.getVideo()).isNotNull();
		Assertions.assertThat(catalogFile.getVideo().getVideoCodec()).isEqualTo("h265");
		Assertions.assertThat(catalogFile.getVideo().getHdr()).isTrue();
		Assertions.assertThat(catalogFile.getLocation().getInventoryPath()).contains("input");

		MetadataResult updated = photoMetadata(captureDate);

		new CatalogFileMapper(mediaDateResolver, Clock.systemDefaultZone()).updateEntity(catalogFile,
				Path.of("C:/input/photo.jpg"), Path.of("C:/input"), updated);

		Assertions.assertThat(catalogFile.getLocation()).isNotNull();
		Assertions.assertThat(catalogFile.getPhoto()).isNotNull();

		assertPhotoMetadata(catalogFile.getPhoto(), captureDate);

		Assertions.assertThat(catalogFile.getVideo()).isNull();
	}

	@Test
	void updateEntityShouldClearPhotoAndVideoForNonMediaSpecificType() {
		LocalDateTime captureDate = LocalDateTime.of(2024, Month.MAY, 9, 10, 30);

		MetadataResult metadata = MetadataResult.builder().fileName("doc.pdf").extension("pdf").sizeBytes(10L)
				.mimeType("application/pdf").fileType(FileType.PDF).createdAt(captureDate).modifiedAt(captureDate)
				.captureDate(captureDate).dateSource(DateSource.FILE_CREATED_AT).subcategory(MediaSubcategory.OTHER)
				.build();

		CatalogFile catalogFile = CatalogFile.builder().photo(Photo.builder().build()).video(Video.builder().build())
				.build();

		new CatalogFileMapper(mediaDateResolver, Clock.systemDefaultZone()).updateEntity(catalogFile,
				Path.of("C:/input/doc.pdf"), Path.of("C:/input"), metadata);

		Assertions.assertThat(catalogFile.getPhoto()).isNull();
		Assertions.assertThat(catalogFile.getVideo()).isNull();
		Assertions.assertThat(catalogFile.getMetadata().getCategory()).isEqualTo(FileCategory.DOCUMENT);
	}

	@Test
	void shouldNormalizeOnlyExactZeroPairAtPersistenceBoundary() {
		LocalDateTime captureDate = LocalDateTime.of(2024, Month.MAY, 9, 10, 30);

		CatalogFileMapper mapper = new CatalogFileMapper(mediaDateResolver, Clock.systemDefaultZone());

		MetadataResult zeroPair = coordinatesMetadata(captureDate, 0.0, 0.0);

		CatalogFile missingGps = mapper.toEntity(Path.of("C:/input/zero.mp4"), Path.of("C:/input"), zeroPair);
		Assertions.assertThat(missingGps.getMetadata().getLatitude()).isNull();
		Assertions.assertThat(missingGps.getMetadata().getLongitude()).isNull();

		MetadataResult equator = coordinatesMetadata(captureDate, 0.0, -45.0);

		CatalogFile validEquator = mapper.toEntity(Path.of("C:/input/equator.mp4"), Path.of("C:/input"), equator);
		Assertions.assertThat(validEquator.getMetadata().getLatitude()).isZero();
		Assertions.assertThat(validEquator.getMetadata().getLongitude()).isEqualTo(-45.0);

		MetadataResult meridian = coordinatesMetadata(captureDate, -23.0, 0.0);

		CatalogFile validMeridian = mapper.toEntity(Path.of("C:/input/meridian.mp4"), Path.of("C:/input"), meridian);
		Assertions.assertThat(validMeridian.getMetadata().getLatitude()).isEqualTo(-23.0);
		Assertions.assertThat(validMeridian.getMetadata().getLongitude()).isZero();
	}

	private MetadataResult coordinatesMetadata(LocalDateTime captureDate, Double latitude, Double longitude) {
		return MetadataResult.builder().fileName("video.mp4").extension("mp4").sizeBytes(1L).mimeType("video/mp4")
				.fileType(FileType.VIDEO).createdAt(captureDate).modifiedAt(captureDate).captureDate(captureDate)
				.dateSource(DateSource.MEDIA_INFO).subcategory(MediaSubcategory.CAMERA).latitude(latitude)
				.longitude(longitude).build();
	}

	private MetadataResult photoMetadata(LocalDateTime captureDate) {
		return MetadataResult.builder().fileName("photo.jpg").extension("jpg").sizeBytes(123L).sha256("sha").md5("md5")
				.mimeType("image/jpeg").fileType(FileType.PHOTO).createdAt(captureDate).modifiedAt(captureDate)
				.captureDate(captureDate).dateSource(DateSource.EXIF).subcategory(MediaSubcategory.CAMERA)
				.storedWidth(4000).storedHeight(3000).displayWidth(4000).displayHeight(3000).orientationCode(1)
				.rotation(0).orientationType(MediaOrientation.LANDSCAPE).manufacturer("Canon").model("R6")
				.latitude(-23.5).longitude(-46.6).metadataJson("{}").exifDate(captureDate).exifJson("{exif}").iso(100)
				.flash("No Flash").exposureTime("1/100").fNumber("2.8").focalLength("50mm").lensModel("RF")
				.whiteBalance("Auto").exposureMode("Auto").exposureProgram("Program").meteringMode("Pattern").build();
	}

	private MetadataResult videoMetadata(LocalDateTime captureDate) {
		return MetadataResult.builder().fileName("video.mp4").extension("mp4").sizeBytes(456L).mimeType("video/mp4")
				.fileType(FileType.VIDEO).createdAt(captureDate).modifiedAt(captureDate).captureDate(captureDate)
				.dateSource(DateSource.MEDIA_INFO).subcategory(MediaSubcategory.GOPRO).storedWidth(3840)
				.storedHeight(2160).displayWidth(3840).displayHeight(2160).orientationType(MediaOrientation.LANDSCAPE)
				.container("mov").videoCodec("h265").audioCodec("aac").videoProfile("main").fps(59.94)
				.videoBitrate(1000L).totalBitrate(1200L).durationSeconds(10.5).hdr(true).mediaInfoJson("{media}")
				.pixelFormat("yuv420p").colorSpace("bt2020").colorTransfer("smpte2084").colorPrimaries("bt2020")
				.bitDepth(10).audioSampleRate(48000).audioChannels(2).audioChannelLayout("stereo").build();
	}

	private void assertPhotoMetadata(Photo photo, LocalDateTime captureDate) {
		Assertions.assertThat(photo).extracting(Photo::getFormat, Photo::getExifDate, Photo::getExifJson, Photo::getIso,
				Photo::getFlash, Photo::getExposureTime, Photo::getFNumber, Photo::getFocalLength, Photo::getLensModel,
				Photo::getWhiteBalance, Photo::getExposureMode, Photo::getExposureProgram, Photo::getMeteringMode)
				.containsExactly("jpg", captureDate, "{exif}", 100, "No Flash", "1/100", "2.8", "50mm", "RF", "Auto",
						"Auto", "Program", "Pattern");
	}

	private void assertVideoMetadata(Video video) {
		Assertions.assertThat(video)
				.extracting(Video::getContainer, Video::getVideoCodec, Video::getAudioCodec, Video::getVideoProfile,
						Video::getFps, Video::getVideoBitrate, Video::getTotalBitrate, Video::getDurationSeconds,
						Video::getHdr, Video::getMediaInfoJson, Video::getPixelFormat, Video::getColorSpace,
						Video::getColorTransfer, Video::getColorPrimaries, Video::getBitDepth,
						Video::getAudioSampleRate, Video::getAudioChannels, Video::getAudioChannelLayout)
				.containsExactly("mov", "h265", "aac", "main", 59.94, 1000L, 1200L, 10.5, true, "{media}", "yuv420p",
						"bt2020", "smpte2084", "bt2020", 10, 48000, 2, "stereo");
	}
}