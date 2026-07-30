package br.com.jorgemelo.nimbusfilemanager.metadata.application.classifier;

import java.time.Clock;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.AirBrushMediaFamily;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.CameraMediaFamily;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.DroneMediaFamily;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.GoProMediaFamily;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.ImageUuidMediaFamily;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.PeachyMediaFamily;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.PhotoGridMediaFamily;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.ScreenshotMediaFamily;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;

/**
 * Subcategory facet (name/folder detection, subcategory, ordering key) of each
 * migrated media family. WhatsApp is covered by WhatsAppMediaFamilyTest.
 */
class MediaSubcategoryRulesTest {

	@Test
	void airBrushShouldSupportPrefixOnly() {
		AirBrushMediaFamily rule = new AirBrushMediaFamily(Clock.systemDefaultZone());

		Assertions.assertThat(rule.supports("AirBrush_20240102_103000.jpg", "C:/photos/AirBrush.jpg")).isTrue();
		Assertions.assertThat(rule.supports("photo.jpg", "C:/photos/photo.jpg")).isFalse();
		Assertions.assertThat(rule.subcategory()).isEqualTo(MediaSubcategory.AIRBRUSH);
		Assertions.assertThat(rule.name()).isEqualTo("020_AIRBRUSH");
	}

	@Test
	void photoGridShouldSupportPrefixOnly() {
		PhotoGridMediaFamily rule = new PhotoGridMediaFamily(Clock.systemDefaultZone());

		Assertions.assertThat(rule.supports("PhotoGrid_1443567518248.jpg", "C:/photos/PhotoGrid.jpg")).isTrue();
		Assertions.assertThat(rule.supports("photo.jpg", "C:/photos/photo.jpg")).isFalse();
		Assertions.assertThat(rule.subcategory()).isEqualTo(MediaSubcategory.PHOTOGRID);
		Assertions.assertThat(rule.name()).isEqualTo("022_PHOTOGRID");
	}

	@Test
	void screenshotShouldSupportFileNameOrFolder() {
		ScreenshotMediaFamily rule = new ScreenshotMediaFamily(Clock.systemDefaultZone());

		Assertions.assertThat(rule.supports("Screenshot_20240102_103000.png", "C:/photos/Screenshot.png")).isTrue();
		Assertions.assertThat(rule.supports("ScreenRecord_20240102_103000.mp4", "C:/x")).isTrue();
		Assertions.assertThat(rule.supports("Captura_20240102.png", "C:/x")).isTrue();
		Assertions.assertThat(rule.supports("photo.jpg", "C:/media/CAPTURA/photo.jpg")).isTrue();
		Assertions.assertThat(rule.supports("photo.jpg", "C:/media/photo.jpg")).isFalse();
		Assertions.assertThat(rule.subcategory()).isEqualTo(MediaSubcategory.SCREENSHOT);
		Assertions.assertThat(rule.name()).isEqualTo("030_SCREENSHOT");
	}

	@Test
	void droneShouldSupportFileNameOrFolder() {
		DroneMediaFamily rule = new DroneMediaFamily();

		Assertions.assertThat(rule.supports("DJI_20240102103000_0001.JPG", "C:/photos/DJI_0001.JPG")).isTrue();
		Assertions.assertThat(rule.supports("photo.jpg", "C:/media/DJI/photo.jpg")).isTrue();
		Assertions.assertThat(rule.supports("photo.jpg", "C:/media/photo.jpg")).isFalse();
		Assertions.assertThat(rule.subcategory()).isEqualTo(MediaSubcategory.DRONE);
		Assertions.assertThat(rule.name()).isEqualTo("040_DRONE");
	}

	@Test
	void goProShouldSupportFileNameOrFolder() {
		GoProMediaFamily rule = new GoProMediaFamily();

		Assertions.assertThat(rule.supports("GOPR0123.MP4", "C:/x")).isTrue();
		Assertions.assertThat(rule.supports("GH010123.MP4", "C:/videos/GH010123.MP4")).isTrue();
		Assertions.assertThat(rule.supports("GX010123.MP4", "C:/x")).isTrue();
		Assertions.assertThat(rule.supports("photo.jpg", "C:/media/GOPRO/photo.jpg")).isTrue();
		Assertions.assertThat(rule.supports("photo.jpg", "C:/media/photo.jpg")).isFalse();
		Assertions.assertThat(rule.subcategory()).isEqualTo(MediaSubcategory.GOPRO);
		Assertions.assertThat(rule.name()).isEqualTo("050_GOPRO");
	}

	@Test
	void cameraShouldSupportDateTimeNamesOnlyButExposeFolder() {
		CameraMediaFamily rule = new CameraMediaFamily();

		Assertions.assertThat(rule.supports("20240102_103000.jpg", "C:/x")).isTrue();
		Assertions.assertThat(rule.supports("2024-01-02 10.30.00.jpg", "C:/x")).isTrue();
		Assertions.assertThat(rule.supports("IMG_20240102.jpg", "C:/x")).isTrue();
		Assertions.assertThat(rule.supports("VID_20240102.mp4", "C:/x")).isTrue();
		Assertions.assertThat(rule.supports("DSC_1234.jpg", "C:/x")).isTrue();
		Assertions.assertThat(rule.supports("PXL_20240102.jpg", "C:/x")).isTrue();
		Assertions.assertThat(rule.supports("random.jpg", "C:/photos/random.jpg")).isFalse();
		// Folder is not part of the subcategory match (mirrors the former rule) but is
		// exposed for the organization rule.
		Assertions.assertThat(CameraMediaFamily.matchesPath("C:/media/CAMERA/photo.jpg")).isTrue();
		Assertions.assertThat(rule.subcategory()).isEqualTo(MediaSubcategory.CAMERA);
		Assertions.assertThat(rule.name()).isEqualTo("060_CAMERA");
	}

	/**
	 * Burst and time-lapse frames belong to the same camera as the GOPR shots, and
	 * the rule runs before the camera family so they are not classified as generic
	 * camera output.
	 */
	@Test
	void goProShouldRecognizeTheBurstSequenceWithoutSweepingInOtherG0Names() {
		GoProMediaFamily rule = new GoProMediaFamily();

		Assertions.assertThat(rule.supports("G0010042.JPG", "C:/x")).isTrue();
		Assertions.assertThat(rule.supports("g0010042 (1).jpg", "C:/x")).isTrue();

		Assertions.assertThat(rule.supports("G001004.JPG", "C:/x")).isFalse();
		Assertions.assertThat(rule.supports("G00100425.JPG", "C:/x")).isFalse();
		Assertions.assertThat(rule.supports("G0 relatorio.jpg", "C:/x")).isFalse();
	}

	@Test
	void cameraShouldRecognizeTheCompactCameraSequences() {
		CameraMediaFamily rule = new CameraMediaFamily();

		Assertions.assertThat(rule.supports("DSC00021.JPG", "C:/x")).isTrue();
		Assertions.assertThat(rule.supports("DSCN0001.JPG", "C:/x")).isTrue();
		Assertions.assertThat(rule.supports("GEDC0002.JPG", "C:/x")).isTrue();
		Assertions.assertThat(rule.supports("IMGP4319.JPG", "C:/x")).isTrue();
		Assertions.assertThat(rule.supports("CIMG3726.jpg", "C:/x")).isTrue();
		Assertions.assertThat(rule.supports("S2020029.jpg", "C:/x")).isTrue();
		Assertions.assertThat(rule.supports("P1010033.JPG", "C:/x")).isTrue();
		Assertions.assertThat(rule.supports("100_1086.JPG", "C:/x")).isTrue();
		Assertions.assertThat(rule.supports("GEDC0002 (1).JPG", "C:/x")).isTrue();
	}

	/**
	 * The digit count is what separates a device sequence from any other numeric
	 * name, so a longer run - a social-network id, an epoch timestamp - must not be
	 * read as camera output.
	 */
	@Test
	void cameraShouldRejectSequencesWithTheWrongDigitCount() {
		CameraMediaFamily rule = new CameraMediaFamily();

		Assertions.assertThat(rule.supports("DSC0002.JPG", "C:/x")).isFalse();
		Assertions.assertThat(rule.supports("DSC000210.JPG", "C:/x")).isFalse();
		Assertions.assertThat(rule.supports("S20200290.jpg", "C:/x")).isFalse();
		Assertions.assertThat(rule.supports("P101003.JPG", "C:/x")).isFalse();
		Assertions.assertThat(rule.supports("1000_1086.JPG", "C:/x")).isFalse();
		Assertions.assertThat(rule.supports("Suellen 01.jpg", "C:/x")).isFalse();
	}

	@Test
	void peachyShouldMapToOther() {
		PeachyMediaFamily rule = new PeachyMediaFamily(Clock.systemDefaultZone());

		Assertions.assertThat(rule.supports("Peachy_20240102_103000.jpg", "C:/photos/Peachy.jpg")).isTrue();
		Assertions.assertThat(rule.supports("photo.jpg", "C:/photos/photo.jpg")).isFalse();
		Assertions.assertThat(rule.subcategory()).isEqualTo(MediaSubcategory.OTHER);
		Assertions.assertThat(rule.name()).isEqualTo("070_PEACHY");
	}

	@Test
	void imageUuidShouldMapToCamera() {
		ImageUuidMediaFamily rule = new ImageUuidMediaFamily(Clock.systemDefaultZone());

		Assertions.assertThat(rule.supports("IMAGE_20240102_103000.jpg", "C:/photos/IMAGE_uuid.jpg")).isTrue();
		Assertions.assertThat(rule.supports("photo.jpg", "C:/photos/photo.jpg")).isFalse();
		Assertions.assertThat(rule.subcategory()).isEqualTo(MediaSubcategory.CAMERA);
		Assertions.assertThat(rule.name()).isEqualTo("080_IMAGE_UUID");
	}

	/**
	 * The name reaches the families from {@code Path#getFileName()}, which answers
	 * null for a root path: no family may claim such an item, and none may fail on
	 * it either - a single throw here would abort the whole classification.
	 */
	@Test
	void noFamilyClaimsAnItemWhoseNameIsUnavailable() {
		Assertions.assertThat(CameraMediaFamily.matchesName(null)).isFalse();
		Assertions.assertThat(DroneMediaFamily.matchesName(null)).isFalse();
		Assertions.assertThat(GoProMediaFamily.matchesName(null)).isFalse();
		Assertions.assertThat(ImageUuidMediaFamily.matchesName(null)).isFalse();
		Assertions.assertThat(PeachyMediaFamily.matchesName(null)).isFalse();
		Assertions.assertThat(ScreenshotMediaFamily.matchesName(null)).isFalse();
	}
}