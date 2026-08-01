package br.com.jorgemelo.nimbusfilemanager.metadata.application.classifier;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.AirBrushMediaFamily;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.CameraMediaFamily;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.DroneMediaFamily;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.GoProMediaFamily;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.ImageUuidMediaFamily;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.PeachyMediaFamily;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.ScreenshotMediaFamily;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.family.WhatsAppMediaFamily;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;

/**
 * Exercises {@link MediaSubcategoryResolver} wired to the real
 * {@link MediaSubcategoryRuleEngine} and every production
 * {@link MediaSubcategoryRule}, the same composition Spring assembles at
 * runtime - so this stays an end-to-end check of the classification behavior,
 * not just of the engine's dispatch logic (covered separately by
 * {@link MediaSubcategoryRuleEngineTest}).
 */
class MediaSubcategoryResolverTest {

	private final MediaSubcategoryResolver resolver = new MediaSubcategoryResolver(
			new MediaSubcategoryRuleEngine(List.of(new WhatsAppMediaFamily(Clock.systemDefaultZone()),
					new AirBrushMediaFamily(Clock.systemDefaultZone()),
					new ScreenshotMediaFamily(Clock.systemDefaultZone()), new DroneMediaFamily(),
					new GoProMediaFamily(), new CameraMediaFamily(), new PeachyMediaFamily(Clock.systemDefaultZone()),
					new ImageUuidMediaFamily(Clock.systemDefaultZone()))));

	@Test
	void shouldResolveSubcategoryFromFileNameOrPath() {
		Assertions.assertThat(resolver.resolve(Path.of("IMG-20240102-WA0001.jpg")))
				.isEqualTo(MediaSubcategory.WHATSAPP);
		Assertions.assertThat(resolver.resolve(Path.of("AirBrush_20240102_103000.jpg")))
				.isEqualTo(MediaSubcategory.AIRBRUSH);
		Assertions.assertThat(resolver.resolve(Path.of("Screenshot_20240102_103000.png")))
				.isEqualTo(MediaSubcategory.SCREENSHOT);
		Assertions.assertThat(resolver.resolve(Path.of("DJI_20240102103000_0001.JPG")))
				.isEqualTo(MediaSubcategory.DRONE);
		Assertions.assertThat(resolver.resolve(Path.of("GH010123.MP4"))).isEqualTo(MediaSubcategory.GOPRO);
		Assertions.assertThat(resolver.resolve(Path.of("20240102_103000.jpg"))).isEqualTo(MediaSubcategory.CAMERA);
	}

	@Test
	void shouldResolveSubcategoryFromFolder() {
		Assertions.assertThat(resolver.resolve(Path.of("C:/media/WHATSAPP/photo.jpg")))
				.isEqualTo(MediaSubcategory.WHATSAPP);
		Assertions.assertThat(resolver.resolve(Path.of("C:/media/DJI/photo.jpg"))).isEqualTo(MediaSubcategory.DRONE);
	}

	@Test
	void shouldFallbackForUnknownInput() {
		Assertions.assertThat(resolver.resolve(null)).isEqualTo(MediaSubcategory.UNKNOWN);
		Assertions.assertThat(resolver.resolve(Path.of("unknown.bin"))).isEqualTo(MediaSubcategory.UNKNOWN);
		Assertions.assertThat(resolver.resolve(Path.of("Peachy_20240102_103000.jpg")))
				.isEqualTo(MediaSubcategory.OTHER);
	}
}