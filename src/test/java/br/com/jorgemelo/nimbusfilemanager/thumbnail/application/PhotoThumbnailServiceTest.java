package br.com.jorgemelo.nimbusfilemanager.thumbnail.application;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.image.PhotoDecoder;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import br.com.jorgemelo.nimbusfilemanager.thumbnail.application.dto.PhotoThumbnail;
import br.com.jorgemelo.nimbusfilemanager.thumbnail.application.dto.PhotoThumbnailSource;
import br.com.jorgemelo.nimbusfilemanager.thumbnail.infrastructure.persistence.PhotoThumbnailRepository;

@ExtendWith(MockitoExtension.class)
class PhotoThumbnailServiceTest {

	@Mock
	private PhotoThumbnailRepository repository;
	@Mock
	private WorkspaceManager workspaceManager;
	@TempDir
	Path temp;

	@Test
	void shouldGenerateNormalizedCachedThumbnailWithRotation() throws Exception {
		UUID id = UUID.randomUUID();

		Path source = temp.resolve("source.png");

		BufferedImage original = new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB);
		var graphics = original.createGraphics();
		graphics.setColor(Color.BLUE);
		graphics.fillRect(0, 0, 800, 400);
		graphics.dispose();
		ImageIO.write(original, "png", source.toFile());

		LocalDateTime modified = LocalDateTime.of(2026, Month.JULY, 11, 10, 0);

		when(repository.findSource(id))
				.thenReturn(Optional.of(new PhotoThumbnailSource(id, source.toString(), modified, 90)));

		String key = id + "-" + modified.toEpochSecond(ZoneOffset.UTC) + "-w320";

		Path target = temp.resolve("cache").resolve(key + ".jpg");

		when(workspaceManager.resolve("cache", "thumbnails", id.toString().substring(0, 2), key + ".jpg"))
				.thenReturn(target);

		PhotoThumbnailService service = new PhotoThumbnailService(repository, workspaceManager,
				new PhotoDecoder());

		PhotoThumbnail first = service.get(id, 200).orElseThrow();
		PhotoThumbnail second = service.get(id, 320).orElseThrow();

		BufferedImage result = ImageIO.read(first.path().toFile());

		Assertions.assertThat(first.path()).isEqualTo(target).exists();
		Assertions.assertThat(second.path()).isEqualTo(first.path());
		Assertions.assertThat(result.getWidth()).isEqualTo(320);
		Assertions.assertThat(result.getHeight()).isEqualTo(640);
		Assertions.assertThat(first.etag()).contains(id.toString(), "w320");

		verify(repository, times(2)).findSource(id);
	}

	@Test
	void shouldReturnEmptyForUnknownOrNonPhotoMedia() throws Exception {
		UUID id = UUID.randomUUID();

		when(repository.findSource(id)).thenReturn(Optional.empty());

		Assertions.assertThat(new PhotoThumbnailService(repository, workspaceManager,
				new PhotoDecoder()).get(id, 320)).isEmpty();
	}

	@Test
	void shouldReturnEmptyWhenSourceFormatCannotBeDecoded() throws Exception {
		UUID id = UUID.randomUUID();

		Path source = temp.resolve("sticker.webp");
		Files.writeString(source, "not a decodable image");

		LocalDateTime modified = LocalDateTime.of(2026, Month.JULY, 11, 10, 0);

		when(repository.findSource(id))
				.thenReturn(Optional.of(new PhotoThumbnailSource(id, source.toString(), modified, 0)));

		String key = id + "-" + modified.toEpochSecond(ZoneOffset.UTC) + "-w320";

		Path target = temp.resolve("cache").resolve(key + ".jpg");

		when(workspaceManager.resolve("cache", "thumbnails", id.toString().substring(0, 2), key + ".jpg"))
				.thenReturn(target);

		// Undecodable format (e.g. WEBP/HEIC) must yield "no thumbnail", not a 500.
		Assertions.assertThat(new PhotoThumbnailService(repository, workspaceManager,
				new PhotoDecoder()).get(id, 320)).isEmpty();
	}

	/**
	 * The catalog still points at a file that was moved or deleted outside the app:
	 * that is a missing thumbnail, not a server error.
	 */
	@Test
	void shouldReturnEmptyWhenTheSourceFileNoLongerExists() throws Exception {
		UUID id = UUID.randomUUID();

		LocalDateTime modified = LocalDateTime.of(2026, Month.JULY, 11, 10, 0);

		when(repository.findSource(id)).thenReturn(
				Optional.of(new PhotoThumbnailSource(id, temp.resolve("gone.jpg").toString(), modified, 0)));

		String key = id + "-" + modified.toEpochSecond(ZoneOffset.UTC) + "-w320";

		when(workspaceManager.resolve("cache", "thumbnails", id.toString().substring(0, 2), key + ".jpg"))
				.thenReturn(temp.resolve("cache").resolve(key + ".jpg"));

		Assertions.assertThat(new PhotoThumbnailService(repository, workspaceManager, new PhotoDecoder()).get(id, 320))
				.isEmpty();
	}

	/**
	 * A requested width is rounded up to the next offered size so the cache only
	 * ever holds the fixed ladder; anything above the largest offered width is
	 * rejected outright rather than silently clamped.
	 */
	@Test
	void shouldRoundTheRequestedWidthUpToTheNextOfferedSize() throws Exception {
		Path fixture = classpathFixture("photo/webp/lossy.webp");

		UUID id = UUID.randomUUID();

		LocalDateTime modified = LocalDateTime.of(2026, Month.JULY, 11, 10, 0);

		when(repository.findSource(id))
				.thenReturn(Optional.of(new PhotoThumbnailSource(id, fixture.toString(), modified, 0)));

		String key = id + "-" + modified.toEpochSecond(ZoneOffset.UTC) + "-w320";

		Path target = temp.resolve("cache").resolve(key + ".jpg");

		when(workspaceManager.resolve("cache", "thumbnails", id.toString().substring(0, 2), key + ".jpg"))
				.thenReturn(target);

		PhotoThumbnailService service = new PhotoThumbnailService(repository, workspaceManager, new PhotoDecoder());

		Assertions.assertThat(service.get(id, 200)).isPresent();
		Assertions.assertThat(target).exists();
	}

	@Test
	void shouldGenerateThumbnailForWebpSourceWithSameCacheAndEtag() throws Exception {
		Path fixture = classpathFixture("photo/webp/lossy.webp");
		Assumptions.assumeTrue(fixture != null, "missing lossy webp fixture");

		UUID id = UUID.randomUUID();

		Path source = temp.resolve("thumb.webp");
		Files.copy(fixture, source);

		LocalDateTime modified = LocalDateTime.of(2026, Month.JULY, 12, 9, 0);

		when(repository.findSource(id))
				.thenReturn(Optional.of(new PhotoThumbnailSource(id, source.toString(), modified, 0)));

		String key = id + "-" + modified.toEpochSecond(ZoneOffset.UTC) + "-w320";

		Path target = temp.resolve("cache").resolve(key + ".jpg");

		when(workspaceManager.resolve("cache", "thumbnails", id.toString().substring(0, 2), key + ".jpg"))
				.thenReturn(target);

		PhotoThumbnailService service = new PhotoThumbnailService(repository, workspaceManager,
				new PhotoDecoder());

		// WEBP now decodes in-JVM (TwelveMonkeys SPI): a real thumbnail is produced...
		PhotoThumbnail first = service.get(id, 320).orElseThrow();
		// ...cached lazily: the second call reuses the same file, not a regeneration.
		PhotoThumbnail second = service.get(id, 320).orElseThrow();

		BufferedImage jpeg = ImageIO.read(first.path().toFile());

		Assertions.assertThat(first.path()).isEqualTo(target).exists();
		Assertions.assertThat(second.path()).isEqualTo(first.path());
		Assertions.assertThat(first.etag()).isEqualTo('"' + key + '"').contains(id.toString(), "w320");
		Assertions.assertThat(jpeg).isNotNull();
		// The 120px-wide source is never upscaled: min(requested 320, source 120) =
		// 120.
		Assertions.assertThat(jpeg.getWidth()).isEqualTo(120);
	}

	private static Path classpathFixture(String name) throws Exception {
		var url = PhotoThumbnailServiceTest.class.getClassLoader().getResource(name);

		return url == null ? null : Path.of(url.toURI());
	}

	@Test
	void shouldRejectArbitraryThumbnailWidths() {
		PhotoThumbnailService service = new PhotoThumbnailService(repository, workspaceManager,
				new PhotoDecoder());

		Assertions.assertThatIllegalArgumentException().isThrownBy(() -> service.get(UUID.randomUUID(), 0));
		Assertions.assertThatIllegalArgumentException().isThrownBy(() -> service.get(UUID.randomUUID(), 641));
	}
}