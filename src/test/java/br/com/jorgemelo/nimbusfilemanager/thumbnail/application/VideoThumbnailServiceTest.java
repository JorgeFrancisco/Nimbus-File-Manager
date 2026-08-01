package br.com.jorgemelo.nimbusfilemanager.thumbnail.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Api;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Inventory;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Tools;
import br.com.jorgemelo.nimbusfilemanager.thumbnail.application.dto.PhotoThumbnail;
import br.com.jorgemelo.nimbusfilemanager.thumbnail.application.dto.VideoThumbnailSource;
import br.com.jorgemelo.nimbusfilemanager.thumbnail.infrastructure.FfmpegRunner;
import br.com.jorgemelo.nimbusfilemanager.thumbnail.infrastructure.persistence.VideoThumbnailRepository;

@ExtendWith(MockitoExtension.class)
class VideoThumbnailServiceTest {

	@Mock
	VideoThumbnailRepository repository;
	@Mock
	WorkspaceManager workspaceManager;
	@Mock
	AppSettingService appSettingService;
	@TempDir
	Path temp;

	@Test
	void shouldGenerateFrameAtTenPercentAndReusePersistentCache() throws Exception {
		UUID id = UUID.randomUUID();

		Path video = temp.resolve("video.mp4");
		Files.write(video, new byte[] { 1 });

		LocalDateTime modified = LocalDateTime.of(2026, Month.JULY, 11, 12, 0);

		when(repository.findSource(id))
				.thenReturn(Optional.of(new VideoThumbnailSource(id, video.toString(), modified, 50D)));
		when(appSettingService.stringValue(any(), any())).thenReturn("ffmpeg");

		String key = id + "-" + modified.toEpochSecond(ZoneOffset.UTC) + "-video-w320";

		Path target = temp.resolve(key + ".jpg");

		when(workspaceManager.resolve("cache", "thumbnails", id.toString().substring(0, 2), key + ".jpg"))
				.thenReturn(target);

		AtomicInteger runs = new AtomicInteger();

		FfmpegRunner runner = (_, _, output, width, seek) -> {
			Assertions.assertThat(width).isEqualTo(320);
			Assertions.assertThat(seek).isEqualTo(5D);
			Files.write(output, new byte[] { 1, 2, 3 });
			runs.incrementAndGet();
		};

		VideoThumbnailService service = new VideoThumbnailService(repository, workspaceManager, appSettingService,
				properties(), runner);

		PhotoThumbnail first = service.get(id, 200).orElseThrow();
		PhotoThumbnail second = service.get(id, 320).orElseThrow();

		Assertions.assertThat(first.path()).exists().isEqualTo(second.path());
		Assertions.assertThat(runs).hasValue(1);
	}

	@Test
	void shouldReturnEmptyForNonVideoAndRejectLargeWidth() throws Exception {
		UUID id = UUID.randomUUID();

		when(repository.findSource(id)).thenReturn(Optional.empty());

		VideoThumbnailService service = new VideoThumbnailService(repository, workspaceManager, appSettingService,
				properties(), (_, _, _, _, _) -> {
				});

		Assertions.assertThat(service.get(id, 320)).isEmpty();
		Assertions.assertThatIllegalArgumentException().isThrownBy(() -> service.get(id, 641));
	}

	@Test
	void rejectsWidthOutsideTheAllowedRange() {
		VideoThumbnailService service = new VideoThumbnailService(repository, workspaceManager, appSettingService,
				properties(), (_, _, _, _, _) -> {
				});

		Assertions.assertThatIllegalArgumentException().isThrownBy(() -> service.get(UUID.randomUUID(), 0));
		Assertions.assertThatIllegalArgumentException().isThrownBy(() -> service.get(UUID.randomUUID(), 641));
	}

	@Test
	void failsWhenTheVideoSourceIsNotOnDisk() {
		UUID id = UUID.randomUUID();

		LocalDateTime modified = LocalDateTime.of(2026, Month.JULY, 11, 12, 0);

		Path missing = temp.resolve("gone.mp4");

		when(repository.findSource(id))
				.thenReturn(Optional.of(new VideoThumbnailSource(id, missing.toString(), modified, 10D)));

		String key = id + "-" + modified.toEpochSecond(ZoneOffset.UTC) + "-video-w320";

		when(workspaceManager.resolve(any(), any(), any(), any())).thenReturn(temp.resolve(key + ".jpg"));

		VideoThumbnailService service = new VideoThumbnailService(repository, workspaceManager, appSettingService,
				properties(), (_, _, _, _, _) -> {
				});

		Assertions.assertThatThrownBy(() -> service.get(id, 320)).isInstanceOf(IOException.class);
	}

	@Test
	void failsWhenFfmpegProducesAnEmptyThumbnail() throws Exception {
		UUID id = UUID.randomUUID();

		Path video = temp.resolve("v.mp4");
		Files.write(video, new byte[] { 1 });

		LocalDateTime modified = LocalDateTime.of(2026, Month.JULY, 11, 12, 0);

		// durationSeconds null exercises the default-seek branch; width 640 the
		// large-size branch.
		when(repository.findSource(id))
				.thenReturn(Optional.of(new VideoThumbnailSource(id, video.toString(), modified, null)));
		when(appSettingService.stringValue(any(), any())).thenReturn("ffmpeg");

		String key = id + "-" + modified.toEpochSecond(ZoneOffset.UTC) + "-video-w640";

		when(workspaceManager.resolve(any(), any(), any(), any())).thenReturn(temp.resolve(key + ".jpg"));

		VideoThumbnailService service = new VideoThumbnailService(repository, workspaceManager, appSettingService,
				properties(), (_, _, _, _, _) -> {
					// leaves the temp output empty -> "FFmpeg produced no thumbnail"
				});

		Assertions.assertThatThrownBy(() -> service.get(id, 640)).isInstanceOf(IOException.class);
	}

	private NimbusFileManagerProperties properties() {
		return new NimbusFileManagerProperties(temp.toString(), new Tools(null, "ffmpeg", true),
				new Inventory(true, 60_000L), new Api(100, 2, 50), null, null);
	}
}