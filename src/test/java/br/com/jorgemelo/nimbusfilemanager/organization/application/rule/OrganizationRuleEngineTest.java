package br.com.jorgemelo.nimbusfilemanager.organization.application.rule;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationRuleResult;
import br.com.jorgemelo.nimbusfilemanager.organization.application.rule.impl.CameraOrganizationRule;
import br.com.jorgemelo.nimbusfilemanager.organization.application.rule.impl.DefaultOrganizationRule;
import br.com.jorgemelo.nimbusfilemanager.organization.application.rule.impl.DroneOrganizationRule;
import br.com.jorgemelo.nimbusfilemanager.organization.application.rule.impl.GoProOrganizationRule;
import br.com.jorgemelo.nimbusfilemanager.organization.application.rule.impl.ScreenshotOrganizationRule;
import br.com.jorgemelo.nimbusfilemanager.organization.application.rule.impl.WhatsAppOrganizationRule;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationRuleReason;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationRuleType;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.projection.OrganizationCandidate;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;

class OrganizationRuleEngineTest {

	private final OrganizationRuleEngine engine = new OrganizationRuleEngine(
			List.of(new DefaultOrganizationRule(), new CameraOrganizationRule(), new ScreenshotOrganizationRule(),
					new GoProOrganizationRule(), new DroneOrganizationRule(), new WhatsAppOrganizationRule()));

	@Test
	void shouldClassifySpecificRulesBeforeDefaultRule() {
		assertClassification(candidate("IMG-20240102-WA0001.jpg", "C:/media/input", MediaSubcategory.OTHER),
				OrganizationRuleType.WHATSAPP, OrganizationRuleReason.FILE_NAME, MediaSubcategory.WHATSAPP);

		assertClassification(candidate("DJI_20240102103000_0001.JPG", "C:/media/input", MediaSubcategory.OTHER),
				OrganizationRuleType.DRONE, OrganizationRuleReason.FILE_NAME, MediaSubcategory.DRONE);

		assertClassification(candidate("GH010123.MP4", "C:/media/input", MediaSubcategory.OTHER),
				OrganizationRuleType.GOPRO, OrganizationRuleReason.FILE_NAME, MediaSubcategory.GOPRO);

		assertClassification(candidate("Screenshot_20240102_103000.png", "C:/media/input", MediaSubcategory.OTHER),
				OrganizationRuleType.SCREENSHOT, OrganizationRuleReason.FILE_NAME, MediaSubcategory.SCREENSHOT);

		assertClassification(candidate("20240102_103000.jpg", "C:/media/input", MediaSubcategory.OTHER),
				OrganizationRuleType.CAMERA, OrganizationRuleReason.FILE_NAME, MediaSubcategory.CAMERA);
	}

	@Test
	void shouldPreferDatabaseReasonWhenSubcategoryIsAlreadyKnown() {
		OrganizationRuleResult result = engine
				.classify(candidate("random.jpg", "C:/media/input", MediaSubcategory.WHATSAPP));

		Assertions.assertThat(result.rule()).isEqualTo(OrganizationRuleType.WHATSAPP);
		Assertions.assertThat(result.reason()).isEqualTo(OrganizationRuleReason.DATABASE);
		Assertions.assertThat(result.subcategory()).isEqualTo(MediaSubcategory.WHATSAPP);
	}

	@Test
	void shouldFallbackToDefaultRule() {
		OrganizationRuleResult result = engine
				.classify(candidate("unknown.bin", "C:/media/input", MediaSubcategory.OTHER));

		Assertions.assertThat(result.rule()).isEqualTo(OrganizationRuleType.DEFAULT);
		Assertions.assertThat(result.reason()).isEqualTo(OrganizationRuleReason.UNKNOWN);
		Assertions.assertThat(result.category()).isEqualTo(FileCategory.MEDIA);
		Assertions.assertThat(result.fileType()).isEqualTo(FileType.PHOTO);
	}

	/**
	 * A subcategory already resolved in the catalog short-circuits detection for
	 * every family, not just WhatsApp - the filename is never even looked at.
	 */
	@Test
	void shouldPreferTheStoredSubcategoryForEveryFamily() {
		assertClassification(candidate("random.jpg", "C:/media/input", MediaSubcategory.DRONE),
				OrganizationRuleType.DRONE, OrganizationRuleReason.DATABASE, MediaSubcategory.DRONE);

		assertClassification(candidate("random.jpg", "C:/media/input", MediaSubcategory.GOPRO),
				OrganizationRuleType.GOPRO, OrganizationRuleReason.DATABASE, MediaSubcategory.GOPRO);

		assertClassification(candidate("random.jpg", "C:/media/input", MediaSubcategory.CAMERA),
				OrganizationRuleType.CAMERA, OrganizationRuleReason.DATABASE, MediaSubcategory.CAMERA);

		assertClassification(candidate("random.jpg", "C:/media/input", MediaSubcategory.SCREENSHOT),
				OrganizationRuleType.SCREENSHOT, OrganizationRuleReason.DATABASE, MediaSubcategory.SCREENSHOT);
	}

	/**
	 * When neither the catalog nor the filename says anything, the folder the file
	 * sits in still identifies the family - that is how a renamed export inside a
	 * "DJI" or "GoPro" folder is still organized correctly.
	 */
	@Test
	void shouldFallBackToTheContainingFolderForEveryFamily() {
		assertClassification(candidate("random.jpg", "C:/media/DJI", MediaSubcategory.OTHER),
				OrganizationRuleType.DRONE, OrganizationRuleReason.FOLDER, MediaSubcategory.DRONE);

		assertClassification(candidate("random.jpg", "C:/media/GOPRO", MediaSubcategory.OTHER),
				OrganizationRuleType.GOPRO, OrganizationRuleReason.FOLDER, MediaSubcategory.GOPRO);

		assertClassification(candidate("random.jpg", "C:/media/CAMERA", MediaSubcategory.OTHER),
				OrganizationRuleType.CAMERA, OrganizationRuleReason.FOLDER, MediaSubcategory.CAMERA);

		assertClassification(candidate("random.jpg", "C:/media/CAPTURA", MediaSubcategory.OTHER),
				OrganizationRuleType.SCREENSHOT, OrganizationRuleReason.FOLDER, MediaSubcategory.SCREENSHOT);
	}

	private void assertClassification(OrganizationCandidate candidate, OrganizationRuleType rule,
			OrganizationRuleReason reason, MediaSubcategory subcategory) {
		OrganizationRuleResult result = engine.classify(candidate);

		Assertions.assertThat(result.rule()).isEqualTo(rule);
		Assertions.assertThat(result.reason()).isEqualTo(reason);
		Assertions.assertThat(result.subcategory()).isEqualTo(subcategory);
	}

	private OrganizationCandidate candidate(String fileName, String currentPath, MediaSubcategory subcategory) {
		return new OrganizationCandidate(1L, fileName, "jpg", FileType.PHOTO, 100L, currentPath, "C:/media", 2024, 1, 2,
				"202401", LocalDateTime.of(2024, Month.JANUARY, 2, 10, 30), FileCategory.MEDIA, subcategory);
	}

	/**
	 * A file saved by WhatsApp keeps no marker in its name once renamed, so the
	 * folder it sits in is what still identifies it.
	 */
	@Test
	void shouldClassifyByFolderWhenTheNameNoLongerSaysWhatsApp() {
		OrganizationRuleResult result = engine.classify(
				candidate("renamed.jpg", "C:/media/WhatsApp/Media/WhatsApp Images", MediaSubcategory.OTHER));

		Assertions.assertThat(result.rule()).isEqualTo(OrganizationRuleType.WHATSAPP);
		Assertions.assertThat(result.reason()).isEqualTo(OrganizationRuleReason.FOLDER);
		Assertions.assertThat(result.subcategory()).isEqualTo(MediaSubcategory.WHATSAPP);
	}
}