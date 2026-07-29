package br.com.jorgemelo.nimbusfilemanager.metadata.application.extractor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.Coordinates;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.MediaOrientationResolver;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.MediaProcessingPolicy;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.MimeTypeService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.classifier.MediaSubcategoryResolver;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.date.CaptureDateRefiner;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.date.DateSourceService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.FileSystemDates;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataOptions;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.PhotoMetadata;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.ResolvedCaptureDate;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.StoredDimensions;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoMetadata;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.model.MetadataResult;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.shared.util.ExtensionUtils;

@Service
public class MetadataExtractor {

	private final MimeTypeService mimeTypeService;
	private final FileHashService fileHashService;
	private final DateSourceService dateSourceService;
	private final CaptureDateRefiner captureDateRefiner;
	private final MediaMetadataReader mediaMetadataReader;
	private final MediaSubcategoryResolver mediaSubcategoryResolver;
	private final MediaOrientationResolver mediaOrientationResolver;

	public MetadataExtractor(MimeTypeService mimeTypeService, FileHashService fileHashService,
			DateSourceService dateSourceService, CaptureDateRefiner captureDateRefiner,
			MediaMetadataReader mediaMetadataReader, MediaSubcategoryResolver mediaSubcategoryResolver,
			MediaOrientationResolver mediaOrientationResolver) {
		this.mimeTypeService = mimeTypeService;
		this.fileHashService = fileHashService;
		this.dateSourceService = dateSourceService;
		this.captureDateRefiner = captureDateRefiner;
		this.mediaMetadataReader = mediaMetadataReader;
		this.mediaSubcategoryResolver = mediaSubcategoryResolver;
		this.mediaOrientationResolver = mediaOrientationResolver;
	}

	public MetadataResult extract(Path file, MetadataOptions options) {
		if (options == null) {
			options = new MetadataOptions(false, false);
		}

		String mimeType = mimeTypeService.detect(file);

		boolean excludedMediaContainer = MediaProcessingPolicy.isArchiveMasqueradingAsMedia(file, mimeType);

		FileType fileType = excludedMediaContainer ? FileType.OTHER : FileType.resolve(file, mimeType);

		FileSystemDates fileSystemDates = dateSourceService.resolveFileSystemDates(file);
		LocalDateTime createdAt = fileSystemDates.createdAt();
		LocalDateTime modifiedAt = fileSystemDates.modifiedAt();

		String sha256 = null;
		String md5 = null;

		if (options.calculateHashes() && !excludedMediaContainer) {
			var hashes = fileHashService.hashes(file);

			sha256 = hashes.sha256();
			md5 = hashes.md5();
		}

		PhotoMetadata photo = fileType.isPhoto() ? mediaMetadataReader.photo(file) : null;
		VideoMetadata video = fileType.isVideo() ? mediaMetadataReader.video(file) : null;

		LocalDateTime fileNameDate = dateSourceService.resolveFromFileName(file);
		LocalDateTime folderLayoutDate = dateSourceService.resolveFromFolderLayout(file);

		ResolvedCaptureDate resolved = resolveCaptureDate(photo, video, fileNameDate, folderLayoutDate, createdAt,
				modifiedAt);

		LocalDateTime captureDate = resolved.captureDate();

		DateSource dateSource = resolved.dateSource();

		// A name-derived day (midnight) gains the real time-of-day when a filesystem
		// timestamp of the same day corroborates it (source -> FILE_NAME_CONFIRMED).
		var refinedDate = captureDateRefiner.refine(captureDate, dateSource, createdAt, modifiedAt);

		captureDate = refinedDate.captureDate();
		dateSource = refinedDate.dateSource();

		StoredDimensions dimensions = resolveStoredDimensions(photo, video);

		Integer storedWidth = dimensions.width();
		Integer storedHeight = dimensions.height();
		Integer rawRotation = dimensions.rawRotation();

		Integer rotation = mediaOrientationResolver.normalizeRotation(rawRotation);

		Integer displayWidth = mediaOrientationResolver.displayWidth(storedWidth, storedHeight, rotation);
		Integer displayHeight = mediaOrientationResolver.displayHeight(storedWidth, storedHeight, rotation);

		Integer orientationCode = photo != null ? photo.orientationCode() : null;

		MediaSubcategory subcategory = mediaSubcategoryResolver.resolve(file);

		Double latitude;
		Double longitude;

		if (photo != null) {
			latitude = photo.latitude();
			longitude = photo.longitude();
		} else if (video != null) {
			latitude = video.latitude();
			longitude = video.longitude();
		} else {
			latitude = null;
			longitude = null;
		}

		Coordinates coordinates = Coordinates.of(latitude, longitude);

		MetadataResult.MetadataResultBuilder builder = MetadataResult.builder().fileName(file.getFileName().toString())
				.extension(ExtensionUtils.fromPath(file)).sizeBytes(getSize(file))

				.sha256(sha256).md5(md5).mimeType(mimeType).fileType(fileType)

				.createdAt(createdAt).modifiedAt(modifiedAt)

				.captureDate(captureDate).dateSource(dateSource).subcategory(subcategory)

				.storedWidth(storedWidth).storedHeight(storedHeight)

				.displayWidth(displayWidth).displayHeight(displayHeight)

				.orientationCode(orientationCode).rotation(rotation)
				.orientationType(mediaOrientationResolver.orientationType(storedWidth, storedHeight, rotation))

				.latitude(coordinates == null ? null : coordinates.latitude())
				.longitude(coordinates == null ? null : coordinates.longitude())

				.metadataJson(null);

		applyPhotoFields(builder, photo);
		applyVideoFields(builder, video);

		return builder.build();
	}

	private ResolvedCaptureDate resolveCaptureDate(PhotoMetadata photo, VideoMetadata video, LocalDateTime fileNameDate,
			LocalDateTime folderLayoutDate, LocalDateTime createdAt, LocalDateTime modifiedAt) {
		if (photo != null && photo.captureDate() != null) {
			return new ResolvedCaptureDate(photo.captureDate(), DateSource.EXIF);
		} else if (video != null && video.captureDate() != null) {
			return new ResolvedCaptureDate(video.captureDate(), DateSource.MEDIA_INFO);
		} else if (fileNameDate != null) {
			return new ResolvedCaptureDate(fileNameDate, DateSource.FILE_NAME);
		} else if (folderLayoutDate != null) {
			return new ResolvedCaptureDate(folderLayoutDate, DateSource.FOLDER_LAYOUT);
		} else if (modifiedAt != null && (createdAt == null || modifiedAt.isBefore(createdAt))) {
			// No embedded/name/folder date: fall back to the OLDEST filesystem timestamp.
			// On copies/moves the OS often resets "created" to the copy date while
			// "modified"
			// preserves the original mtime, so the earlier one is the more-original signal.
			return new ResolvedCaptureDate(modifiedAt, DateSource.FILE_MODIFIED_AT);
		} else {
			return new ResolvedCaptureDate(createdAt, DateSource.FILE_CREATED_AT);
		}
	}

	private StoredDimensions resolveStoredDimensions(PhotoMetadata photo, VideoMetadata video) {
		if (photo != null) {
			return new StoredDimensions(photo.width(), photo.height(),
					mediaOrientationResolver.exifOrientationToRotation(photo.orientationCode()));
		} else if (video != null) {
			return new StoredDimensions(video.width(), video.height(), video.rotation());
		} else {
			return new StoredDimensions(null, null, null);
		}
	}

	private void applyPhotoFields(MetadataResult.MetadataResultBuilder builder, PhotoMetadata photo) {
		if (photo == null) {
			return;
		}

		builder.manufacturer(photo.manufacturer()).model(photo.model()).iso(photo.iso()).flash(photo.flash())
				.exposureTime(photo.exposureTime()).fNumber(photo.fNumber()).focalLength(photo.focalLength())
				.lensModel(photo.lensModel()).whiteBalance(photo.whiteBalance()).exposureMode(photo.exposureMode())
				.exposureProgram(photo.exposureProgram()).meteringMode(photo.meteringMode())
				.exifDate(photo.captureDate()).exifJson(photo.exifJson());
	}

	private void applyVideoFields(MetadataResult.MetadataResultBuilder builder, VideoMetadata video) {
		if (video == null) {
			return;
		}

		builder.container(video.container()).videoCodec(video.videoCodec()).audioCodec(video.audioCodec())
				.videoProfile(video.videoProfile()).fps(video.fps()).videoBitrate(video.videoBitrate())
				.totalBitrate(video.totalBitrate()).durationSeconds(video.durationSeconds()).hdr(video.hdr())
				.pixelFormat(video.pixelFormat()).colorSpace(video.colorSpace()).colorTransfer(video.colorTransfer())
				.colorPrimaries(video.colorPrimaries()).bitDepth(video.bitDepth())
				.audioSampleRate(video.audioSampleRate()).audioChannels(video.audioChannels())
				.audioChannelLayout(video.audioChannelLayout()).mediaInfoJson(video.mediaInfoJson());
	}

	/**
	 * Decides the media type of a file, giving a <b>known extension precedence over
	 * a misleading content-sniffed MIME</b>. Content detection can report an image
	 * MIME for a non-image file that merely embeds image bytes (e.g. a Delphi
	 * {@code .dfm} form), which previously made such files be cataloged as photos
	 * and sent to ffmpeg for a perceptual hash that always failed (logging a WARN
	 * per file).
	 *
	 * <p>
	 * Rules:
	 * <ul>
	 * <li>a known extension (image, video, audio, document, archive) is
	 * authoritative;</li>
	 * <li>an unknown or missing extension must <b>not</b> be promoted to a media
	 * type by the content sniff (the false-positive case): only a non-media MIME is
	 * accepted, otherwise the file stays {@link FileType#OTHER} and never reaches
	 * ffmpeg/EXIF.</li>
	 * </ul>
	 * Genuinely supported image formats keep working because their extensions are
	 * in {@link FileType#PHOTO}.
	 */
	private Long getSize(Path file) {
		try {
			return Files.size(file);
		} catch (Exception e) {
			throw new IllegalStateException("Could not read file size: " + file, e);
		}
	}
}