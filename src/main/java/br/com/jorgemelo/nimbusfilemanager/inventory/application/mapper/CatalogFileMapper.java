package br.com.jorgemelo.nimbusfilemanager.inventory.application.mapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.Coordinates;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.date.MediaDateResolver;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.ResolvedMediaDate;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.model.MetadataResult;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.MediaMetadata;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Photo;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Video;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

@Component
public class CatalogFileMapper {

	private final MediaDateResolver mediaDateResolver;
	private final Clock clock;

	public CatalogFileMapper(MediaDateResolver mediaDateResolver, Clock clock) {
		this.mediaDateResolver = mediaDateResolver;
		this.clock = clock;
	}

	public void updateEntity(CatalogFile catalogFile, Path file, Path sourcePath, MetadataResult metadata) {
		catalogFile.setFileName(metadata.getFileName());
		catalogFile.setExtension(metadata.getExtension());
		catalogFile.setSizeBytes(metadata.getSizeBytes());
		catalogFile.setSha256(metadata.getSha256());
		catalogFile.setMd5(metadata.getMd5());
		catalogFile.setMimeType(metadata.getMimeType());
		catalogFile.setCreatedAt(metadata.getCreatedAt());
		catalogFile.setModifiedAt(metadata.getModifiedAt());
		catalogFile.setFileType(metadata.getFileType());
		catalogFile.markActive();
		catalogFile.setLastAnalysis(LocalDateTime.now(clock));
		catalogFile.setAnalysisVersion("1");

		updateLocation(catalogFile, file, sourcePath);

		updateMedia(catalogFile, metadata);

		switch (metadata.getFileType()) {
		case PHOTO -> {
			updatePhoto(catalogFile, metadata);

			catalogFile.setVideo(null);
		}
		case VIDEO -> {
			updateVideo(catalogFile, metadata);

			catalogFile.setPhoto(null);
		}
		default -> {
			catalogFile.setPhoto(null);
			catalogFile.setVideo(null);
		}
		}
	}

	private void updateLocation(CatalogFile catalogFile, Path file, Path sourcePath) {
		CatalogFileLocation location = catalogFile.getLocation();

		if (location == null) {
			location = CatalogFileLocation.builder().catalogFile(catalogFile).build();

			catalogFile.setLocation(location);
		}

		Path normalized = PathUtils.normalizePath(file.toString());

		Path parent = requireParent(normalized, "media file");

		location.setCurrentPath(PathUtils.normalize(normalized));
		location.setCurrentFolder(PathUtils.normalize(parent));

		location.setOriginalPath(normalized.toString());
		location.setOriginalFolder(PathUtils.normalize(parent));

		location.setInventoryPath(PathUtils.normalize(sourcePath));
	}

	private void updateMedia(CatalogFile catalogFile, MetadataResult metadata) {
		MediaMetadata media = catalogFile.getMetadata();

		if (media == null) {
			media = MediaMetadata.builder().catalogFile(catalogFile).build();

			catalogFile.setMetadata(media);
		}

		ResolvedMediaDate resolvedDate = mediaDateResolver.resolve(metadata);

		media.setYear(resolvedDate.year());
		media.setMonth(resolvedDate.month());
		media.setDay(resolvedDate.day());
		media.setYearMonth(resolvedDate.yearMonth());
		media.setCaptureDate(resolvedDate.captureDate());
		media.setDateSource(resolvedDate.dateSource());
		media.setCategory(FileType.categoryOf(metadata.getFileType()));
		media.setSubcategory(metadata.getSubcategory());
		media.setStoredWidth(metadata.getStoredWidth());
		media.setStoredHeight(metadata.getStoredHeight());
		media.setDisplayWidth(metadata.getDisplayWidth());
		media.setDisplayHeight(metadata.getDisplayHeight());
		media.setManufacturer(metadata.getManufacturer());
		media.setModel(metadata.getModel());
		media.setOrientationCode(metadata.getOrientationCode());
		media.setRotation(metadata.getRotation());
		media.setOrientationType(metadata.getOrientationType());

		applyCoordinates(media, metadata.getLatitude(), metadata.getLongitude());

		media.setMetadataJson(metadata.getMetadataJson());
	}

	private void updatePhoto(CatalogFile catalogFile, MetadataResult metadata) {
		Photo photo = catalogFile.getPhoto();

		if (photo == null) {
			photo = Photo.builder().catalogFile(catalogFile).build();

			catalogFile.setPhoto(photo);
		}

		photo.setFormat(metadata.getExtension());
		photo.setExifDate(metadata.getExifDate());
		photo.setExifJson(metadata.getExifJson());
		photo.setIso(metadata.getIso());
		photo.setFlash(metadata.getFlash());
		photo.setExposureTime(metadata.getExposureTime());
		photo.setFNumber(metadata.getFNumber());
		photo.setFocalLength(metadata.getFocalLength());
		photo.setLensModel(metadata.getLensModel());
		photo.setWhiteBalance(metadata.getWhiteBalance());
		photo.setExposureMode(metadata.getExposureMode());
		photo.setExposureProgram(metadata.getExposureProgram());
		photo.setMeteringMode(metadata.getMeteringMode());
	}

	private void updateVideo(CatalogFile catalogFile, MetadataResult metadata) {
		Video video = catalogFile.getVideo();

		if (video == null) {
			video = Video.builder().catalogFile(catalogFile).build();

			catalogFile.setVideo(video);
		}

		video.setContainer(metadata.getContainer());
		video.setVideoCodec(metadata.getVideoCodec());
		video.setAudioCodec(metadata.getAudioCodec());
		video.setVideoProfile(metadata.getVideoProfile());

		video.setFps(metadata.getFps());
		video.setVideoBitrate(metadata.getVideoBitrate());
		video.setTotalBitrate(metadata.getTotalBitrate());
		video.setDurationSeconds(metadata.getDurationSeconds());

		video.setHdr(Boolean.TRUE.equals(metadata.getHdr()));

		video.setMediaInfoJson(metadata.getMediaInfoJson());

		video.setPixelFormat(metadata.getPixelFormat());
		video.setColorSpace(metadata.getColorSpace());
		video.setColorTransfer(metadata.getColorTransfer());
		video.setColorPrimaries(metadata.getColorPrimaries());
		video.setBitDepth(metadata.getBitDepth());
		video.setAudioSampleRate(metadata.getAudioSampleRate());
		video.setAudioChannels(metadata.getAudioChannels());
		video.setAudioChannelLayout(metadata.getAudioChannelLayout());
	}

	public CatalogFile toEntity(Path file, Path sourcePath, MetadataResult metadata) {
		CatalogFile catalogFile = CatalogFile.builder().fileKey(PathUtils.normalize(file))
				.fileName(metadata.getFileName())
				.extension(metadata.getExtension()).sizeBytes(metadata.getSizeBytes()).sha256(metadata.getSha256())
				.md5(metadata.getMd5()).mimeType(metadata.getMimeType()).createdAt(metadata.getCreatedAt())
				.modifiedAt(metadata.getModifiedAt()).fileType(metadata.getFileType())
				.lifecycleStatus(LifecycleStatus.ACTIVE).lastAnalysis(LocalDateTime.now(clock)).analysisVersion("1")
				.build();

		Path normalized = PathUtils.normalizePath(file.toString());

		Path parent = requireParent(normalized, "media file");

		CatalogFileLocation location = CatalogFileLocation.builder().catalogFile(catalogFile)
				.currentPath(normalized.toString())
				.currentFolder(PathUtils.normalize(parent)).originalPath(normalized.toString())
				.originalFolder(PathUtils.normalize(parent)).inventoryPath(PathUtils.normalize(sourcePath)).build();

		catalogFile.setLocation(location);

		MediaMetadata media = buildMedia(catalogFile, metadata);

		catalogFile.setMetadata(media);

		switch (metadata.getFileType()) {
		case PHOTO -> {
			Photo photo = buildPhoto(catalogFile, metadata);

			catalogFile.setPhoto(photo);
		}
		case VIDEO -> {
			Video video = buildVideo(catalogFile, metadata);

			catalogFile.setVideo(video);
		}
		default -> {
			// Other file types (audio/document/other) carry no photo/video block to build.
		}
		}

		return catalogFile;
	}

	private Path requireParent(Path path, String description) {
		Path parent = path.getParent();

		if (parent == null) {
			throw new IllegalArgumentException("A " + description + " path must have a parent directory: " + path);
		}

		return parent;
	}

	private MediaMetadata buildMedia(CatalogFile catalogFile, MetadataResult metadata) {
		ResolvedMediaDate resolvedDate = mediaDateResolver.resolve(metadata);

		MediaMetadata media = MediaMetadata.builder().catalogFile(catalogFile)
				.category(FileType.categoryOf(metadata.getFileType())).subcategory(metadata.getSubcategory())
				.year(resolvedDate.year()).month(resolvedDate.month()).day(resolvedDate.day())
				.yearMonth(resolvedDate.yearMonth()).captureDate(resolvedDate.captureDate())
				.dateSource(resolvedDate.dateSource()).storedWidth(metadata.getStoredWidth())
				.storedHeight(metadata.getStoredHeight()).displayWidth(metadata.getDisplayWidth())
				.displayHeight(metadata.getDisplayHeight()).manufacturer(metadata.getManufacturer())
				.model(metadata.getModel()).orientationCode(metadata.getOrientationCode())
				.rotation(metadata.getRotation()).orientationType(metadata.getOrientationType())
				.metadataJson(metadata.getMetadataJson()).build();

		applyCoordinates(media, metadata.getLatitude(), metadata.getLongitude());

		return media;
	}

	private Photo buildPhoto(CatalogFile catalogFile, MetadataResult metadata) {
		return Photo.builder().catalogFile(catalogFile).format(metadata.getExtension()).exifDate(metadata.getExifDate())
				.exifJson(metadata.getExifJson()).iso(metadata.getIso()).flash(metadata.getFlash())
				.exposureTime(metadata.getExposureTime()).fNumber(metadata.getFNumber())
				.focalLength(metadata.getFocalLength()).lensModel(metadata.getLensModel())
				.whiteBalance(metadata.getWhiteBalance()).exposureMode(metadata.getExposureMode())
				.exposureProgram(metadata.getExposureProgram()).meteringMode(metadata.getMeteringMode()).build();
	}

	private void applyCoordinates(MediaMetadata media, Double latitude, Double longitude) {
		Coordinates coordinates = Coordinates.of(latitude, longitude);

		media.setLatitude(coordinates == null ? null : coordinates.latitude());
		media.setLongitude(coordinates == null ? null : coordinates.longitude());
	}

	private Video buildVideo(CatalogFile catalogFile, MetadataResult metadata) {
		return Video.builder().catalogFile(catalogFile).container(metadata.getContainer())
				.videoCodec(metadata.getVideoCodec()).audioCodec(metadata.getAudioCodec())
				.videoProfile(metadata.getVideoProfile()).fps(metadata.getFps())
				.videoBitrate(metadata.getVideoBitrate()).totalBitrate(metadata.getTotalBitrate())
				.durationSeconds(metadata.getDurationSeconds()).hdr(Boolean.TRUE.equals(metadata.getHdr()))
				.mediaInfoJson(metadata.getMediaInfoJson()).pixelFormat(metadata.getPixelFormat())
				.colorSpace(metadata.getColorSpace()).colorTransfer(metadata.getColorTransfer())
				.colorPrimaries(metadata.getColorPrimaries()).bitDepth(metadata.getBitDepth())
				.audioSampleRate(metadata.getAudioSampleRate()).audioChannels(metadata.getAudioChannels())
				.audioChannelLayout(metadata.getAudioChannelLayout()).build();
	}
}