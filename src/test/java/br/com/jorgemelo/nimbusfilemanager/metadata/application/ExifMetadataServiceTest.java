package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Month;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import com.drew.lang.Rational;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.jpeg.JpegDirectory;

class ExifMetadataServiceTest {

	@Test
	void extractShouldMapExifMetadataAndJson() {
		Metadata metadata = new Metadata();

		ExifSubIFDDirectory subIfd = new ExifSubIFDDirectory();

		ExifIFD0Directory ifd0 = new ExifIFD0Directory();

		subIfd.setInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH, 4000);
		subIfd.setInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT, 3000);
		subIfd.setString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, "2024:05:09 10:30:00");
		subIfd.setInt(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT, 200);
		subIfd.setString(ExifSubIFDDirectory.TAG_LENS_MODEL, "RF 50mm");
		ifd0.setString(ExifIFD0Directory.TAG_MAKE, "Canon");
		ifd0.setString(ExifIFD0Directory.TAG_MODEL, "R5");
		ifd0.setInt(ExifIFD0Directory.TAG_ORIENTATION, 1);
		metadata.addDirectory(subIfd);
		metadata.addDirectory(ifd0);

		var photo = service(metadata).extract(Path.of("C:/photo.jpg"));

		Assertions.assertThat(photo.width()).isEqualTo(4000);
		Assertions.assertThat(photo.height()).isEqualTo(3000);
		Assertions.assertThat(photo.manufacturer()).isEqualTo("Canon");
		Assertions.assertThat(photo.model()).isEqualTo("R5");
		Assertions.assertThat(photo.orientationCode()).isEqualTo(1);
		Assertions.assertThat(photo.iso()).isEqualTo(200);
		Assertions.assertThat(photo.lensModel()).isEqualTo("RF 50mm");
		Assertions.assertThat(photo.captureDate()).isEqualTo(LocalDateTime.of(2024, Month.MAY, 9, 10, 30));
		Assertions.assertThat(photo.exifJson()).contains("Exif SubIFD");
	}

	@Test
	void extractShouldFallbackWidthHeightAndDigitizedDate() {
		Metadata metadata = new Metadata();

		ExifSubIFDDirectory subIfd = new ExifSubIFDDirectory();

		ExifIFD0Directory ifd0 = new ExifIFD0Directory();

		JpegDirectory jpeg = new JpegDirectory();

		subIfd.setString(ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED, "2023:01:02 03:04:05");
		ifd0.setInt(ExifIFD0Directory.TAG_IMAGE_WIDTH, 1920);
		jpeg.setInt(JpegDirectory.TAG_IMAGE_HEIGHT, 1080);
		metadata.addDirectory(subIfd);
		metadata.addDirectory(ifd0);
		metadata.addDirectory(jpeg);

		var photo = service(metadata).extract(Path.of("C:/photo.jpg"));

		Assertions.assertThat(photo.width()).isEqualTo(1920);
		Assertions.assertThat(photo.height()).isEqualTo(1080);
		Assertions.assertThat(photo.captureDate()).isEqualTo(LocalDateTime.of(2023, Month.JANUARY, 2, 3, 4, 5));
	}

	@Test
	void extractShouldMapGpsAndDescriptiveExifFields() {
		Metadata metadata = new Metadata();

		ExifSubIFDDirectory subIfd = new ExifSubIFDDirectory();

		GpsDirectory gps = new GpsDirectory();

		subIfd.setString(ExifSubIFDDirectory.TAG_FLASH, "Flash fired");
		subIfd.setString(ExifSubIFDDirectory.TAG_EXPOSURE_TIME, "1/120 sec");
		subIfd.setString(ExifSubIFDDirectory.TAG_FNUMBER, "f/2.8");
		subIfd.setString(ExifSubIFDDirectory.TAG_FOCAL_LENGTH, "50 mm");
		subIfd.setString(ExifSubIFDDirectory.TAG_WHITE_BALANCE_MODE, "Auto");
		subIfd.setString(ExifSubIFDDirectory.TAG_EXPOSURE_MODE, "Auto exposure");
		subIfd.setString(ExifSubIFDDirectory.TAG_EXPOSURE_PROGRAM, "Manual");
		subIfd.setString(ExifSubIFDDirectory.TAG_METERING_MODE, "Pattern");
		gps.setString(GpsDirectory.TAG_LATITUDE_REF, "S");
		gps.setString(GpsDirectory.TAG_LONGITUDE_REF, "W");
		gps.setRationalArray(GpsDirectory.TAG_LATITUDE,
				new Rational[] { new Rational(23, 1), new Rational(30, 1), new Rational(0, 1) });
		gps.setRationalArray(GpsDirectory.TAG_LONGITUDE,
				new Rational[] { new Rational(46, 1), new Rational(40, 1), new Rational(0, 1) });
		metadata.addDirectory(subIfd);
		metadata.addDirectory(gps);

		var photo = service(metadata).extract(Path.of("C:/photo.jpg"));

		Assertions.assertThat(photo.latitude()).isCloseTo(-23.5, Assertions.offset(0.01));
		Assertions.assertThat(photo.longitude()).isCloseTo(-46.66, Assertions.offset(0.01));
		Assertions.assertThat(photo.flash()).isEqualTo("Flash fired");
		Assertions.assertThat(photo.exposureTime()).isEqualTo("1/120 sec");
		Assertions.assertThat(photo.fNumber()).isEqualTo("f/2.8");
		Assertions.assertThat(photo.focalLength()).isEqualTo("50 mm");
		Assertions.assertThat(photo.whiteBalance()).isEqualTo("Auto");
		Assertions.assertThat(photo.exposureMode()).isEqualTo("Auto exposure");
		Assertions.assertThat(photo.exposureProgram()).isEqualTo("Manual");
		Assertions.assertThat(photo.meteringMode()).isEqualTo("Pattern");
	}

	@Test
	void extractShouldFallbackToJpegWidthAndReturnNullForMissingOrBlankDates() {
		Metadata jpegOnly = new Metadata();

		JpegDirectory jpeg = new JpegDirectory();

		Metadata blankDate = new Metadata();

		ExifSubIFDDirectory subIfd = new ExifSubIFDDirectory();

		jpeg.setInt(JpegDirectory.TAG_IMAGE_WIDTH, 800);
		jpeg.setInt(JpegDirectory.TAG_IMAGE_HEIGHT, 600);
		jpegOnly.addDirectory(jpeg);
		subIfd.setString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, " ");
		subIfd.setString(ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED, " ");
		blankDate.addDirectory(subIfd);

		var dimensions = service(jpegOnly).extract(Path.of("C:/photo.jpg"));
		var noDate = service(new Metadata()).extract(Path.of("C:/photo.jpg"));
		var blank = service(blankDate).extract(Path.of("C:/photo.jpg"));

		Assertions.assertThat(dimensions.width()).isEqualTo(800);
		Assertions.assertThat(dimensions.height()).isEqualTo(600);
		Assertions.assertThat(noDate.captureDate()).isNull();
		Assertions.assertThat(blank.captureDate()).isNull();
	}

	@Test
	void extractShouldReturnEmptyWhenReaderFailsOrDateIsInvalid() {
		Metadata metadata = new Metadata();

		ExifSubIFDDirectory subIfd = new ExifSubIFDDirectory();

		subIfd.setString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL, "bad");
		metadata.addDirectory(subIfd);

		var invalidDate = service(metadata).extract(Path.of("C:/photo.jpg"));
		var failure = new ExifMetadataService(_ -> {
			throw new IllegalStateException("bad");
		}).extract(Path.of("C:/photo.jpg"));

		Assertions.assertThat(invalidDate.captureDate()).isNull();
		Assertions.assertThat(failure.width()).isNull();
		Assertions.assertThat(failure.exifJson()).isNull();
	}

	private ExifMetadataService service(Metadata metadata) {
		return new ExifMetadataService(_ -> metadata);
	}
}