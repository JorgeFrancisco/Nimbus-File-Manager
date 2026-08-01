package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.jpeg.JpegDirectory;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.PhotoMetadata;

/**
 * Reads a photo's EXIF block into {@link PhotoMetadata}. Everything happens
 * in-process through metadata-extractor - no external binary is spawned, which
 * is why the class no longer carries the exiftool name it was born with: the
 * name promised a tool the code never called, and an unused
 * {@code tools.exiftool} path sat on the settings screen for anyone to edit
 * with no effect.
 */
@Service
public class ExifMetadataService {

	private final MetadataReader metadataReader;

	@Autowired
	public ExifMetadataService() {
		this(file -> ImageMetadataReader.readMetadata(file.toFile()));
	}

	ExifMetadataService(MetadataReader metadataReader) {
		this.metadataReader = metadataReader;
	}

	public PhotoMetadata extract(Path file) {
		try {
			Metadata metadata = metadataReader.read(file);

			Integer width = resolveWidth(metadata);
			Integer height = resolveHeight(metadata);

			String manufacturer = getString(metadata, ExifIFD0Directory.class, ExifDirectoryBase.TAG_MAKE);
			String model = getString(metadata, ExifIFD0Directory.class, ExifDirectoryBase.TAG_MODEL);

			ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);

			Integer orientationCode = null;

			if (ifd0 != null) {
				orientationCode = ifd0.getInteger(ExifDirectoryBase.TAG_ORIENTATION);
			}

			LocalDateTime exifDate = getExifDate(metadata);

			GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);

			Double latitude = null;
			Double longitude = null;

			if (gps != null) {
				GeoLocation geo = gps.getGeoLocation();

				if (geo != null && !geo.isZero()) {
					latitude = geo.getLatitude();
					longitude = geo.getLongitude();
				}
			}

			Integer iso = getInteger(metadata, ExifSubIFDDirectory.class, ExifDirectoryBase.TAG_ISO_EQUIVALENT);
			String flash = getString(metadata, ExifSubIFDDirectory.class, ExifDirectoryBase.TAG_FLASH);
			String exposureTime = getString(metadata, ExifSubIFDDirectory.class, ExifDirectoryBase.TAG_EXPOSURE_TIME);
			String fNumber = getString(metadata, ExifSubIFDDirectory.class, ExifDirectoryBase.TAG_FNUMBER);
			String focalLength = getString(metadata, ExifSubIFDDirectory.class, ExifDirectoryBase.TAG_FOCAL_LENGTH);
			String lensModel = getString(metadata, ExifSubIFDDirectory.class, ExifDirectoryBase.TAG_LENS_MODEL);
			String whiteBalance = getString(metadata, ExifSubIFDDirectory.class,
					ExifDirectoryBase.TAG_WHITE_BALANCE_MODE);
			String exposureMode = getString(metadata, ExifSubIFDDirectory.class, ExifDirectoryBase.TAG_EXPOSURE_MODE);
			String exposureProgram = getString(metadata, ExifSubIFDDirectory.class,
					ExifDirectoryBase.TAG_EXPOSURE_PROGRAM);
			String meteringMode = getString(metadata, ExifSubIFDDirectory.class, ExifDirectoryBase.TAG_METERING_MODE);

			return new PhotoMetadata(width, height, manufacturer, model, orientationCode, latitude, longitude, iso,
					flash, exposureTime, fNumber, focalLength, lensModel, whiteBalance, exposureMode, exposureProgram,
					meteringMode, exifDate, toJsonLike(metadata));
		} catch (Exception _) {
			return empty();
		}
	}

	private Integer resolveWidth(Metadata metadata) {
		Integer value = getInteger(metadata, ExifSubIFDDirectory.class, ExifDirectoryBase.TAG_EXIF_IMAGE_WIDTH);

		if (value != null) {
			return value;
		}

		value = getInteger(metadata, ExifIFD0Directory.class, ExifDirectoryBase.TAG_IMAGE_WIDTH);

		if (value != null) {
			return value;
		}

		return getInteger(metadata, JpegDirectory.class, JpegDirectory.TAG_IMAGE_WIDTH);
	}

	private Integer resolveHeight(Metadata metadata) {
		Integer value = getInteger(metadata, ExifSubIFDDirectory.class, ExifDirectoryBase.TAG_EXIF_IMAGE_HEIGHT);

		if (value != null) {
			return value;
		}

		value = getInteger(metadata, ExifIFD0Directory.class, ExifDirectoryBase.TAG_IMAGE_HEIGHT);

		if (value != null) {
			return value;
		}

		return getInteger(metadata, JpegDirectory.class, JpegDirectory.TAG_IMAGE_HEIGHT);
	}

	private LocalDateTime getExifDate(Metadata metadata) {
		ExifSubIFDDirectory directory = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);

		if (directory == null) {
			return null;
		}

		String dateText = directory.getString(ExifDirectoryBase.TAG_DATETIME_ORIGINAL);

		if (dateText == null || dateText.isBlank()) {
			dateText = directory.getString(ExifDirectoryBase.TAG_DATETIME_DIGITIZED);
		}

		if (dateText == null || dateText.isBlank()) {
			return null;
		}

		return parseExifDate(dateText);
	}

	private LocalDateTime parseExifDate(String value) {
		try {
			return LocalDateTime.parse(value.trim(), DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"));
		} catch (Exception _) {
			return null;
		}
	}

	private <T extends Directory> String getString(Metadata metadata, Class<T> type, int tag) {
		T directory = metadata.getFirstDirectoryOfType(type);

		return stripNul(directory != null ? directory.getString(tag) : null);
	}

	/**
	 * PostgreSQL text columns reject the NUL character (code point 0), which some
	 * binary EXIF tags (maker notes, UserComment) carry in their string form. Strip
	 * it so the photo insert (exif_json and the free-text columns) never fails on
	 * such a file. Built from (char) 0 so the source file itself never contains a
	 * raw NUL byte.
	 */
	static String stripNul(String value) {
		return value == null ? null : value.replace(String.valueOf((char) 0), "");
	}

	private <T extends Directory> Integer getInteger(Metadata metadata, Class<T> type, int tag) {
		T directory = metadata.getFirstDirectoryOfType(type);

		return directory != null ? directory.getInteger(tag) : null;
	}

	private String toJsonLike(Metadata metadata) {
		StringBuilder json = new StringBuilder("{");

		boolean first = true;

		for (Directory directory : metadata.getDirectories()) {
			for (var tag : directory.getTags()) {
				if (!first) {
					json.append(",");
				}

				json.append("\"").append(escape(directory.getName())).append(".").append(escape(tag.getTagName()))
						.append("\":\"").append(escape(tag.getDescription())).append("\"");

				first = false;
			}
		}

		json.append("}");

		return json.toString();
	}

	private String escape(String value) {
		if (value == null) {
			return "";
		}

		return stripNul(value).replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private PhotoMetadata empty() {
		return new PhotoMetadata(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
				null, null, null, null, null);
	}
}