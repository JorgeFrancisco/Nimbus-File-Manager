package br.com.jorgemelo.nimbusfilemanager.thumbnail.application;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolPaths;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import br.com.jorgemelo.nimbusfilemanager.thumbnail.application.dto.PhotoThumbnail;
import br.com.jorgemelo.nimbusfilemanager.thumbnail.application.dto.VideoThumbnailSource;
import br.com.jorgemelo.nimbusfilemanager.thumbnail.infrastructure.FfmpegRunner;
import br.com.jorgemelo.nimbusfilemanager.thumbnail.infrastructure.FfmpegVideoThumbnailProcessRunner;
import br.com.jorgemelo.nimbusfilemanager.thumbnail.infrastructure.persistence.VideoThumbnailRepository;

@Service
public class VideoThumbnailService {

	private static final Object[] LOCKS = new Object[32];
	static {
		for (int index = 0; index < LOCKS.length; index++)
			LOCKS[index] = new Object();
	}

	private final VideoThumbnailRepository repository;
	private final WorkspaceManager workspaceManager;
	private final ExternalToolPaths externalToolPaths;
	private final FfmpegRunner ffmpegRunner;

	@Autowired
	public VideoThumbnailService(VideoThumbnailRepository repository, WorkspaceManager workspaceManager,
			ExternalToolPaths externalToolPaths, FfmpegVideoThumbnailProcessRunner processRunner) {
		this(repository, workspaceManager, externalToolPaths, processRunner::run);
	}

	VideoThumbnailService(VideoThumbnailRepository repository, WorkspaceManager workspaceManager,
			ExternalToolPaths externalToolPaths, FfmpegRunner ffmpegRunner) {
		this.repository = repository;
		this.workspaceManager = workspaceManager;
		this.externalToolPaths = externalToolPaths;
		this.ffmpegRunner = ffmpegRunner;
	}

	public Optional<PhotoThumbnail> get(UUID publicId, int requestedWidth) throws IOException {
		int width = normalizeWidth(requestedWidth);

		Optional<VideoThumbnailSource> source = repository.findSource(publicId);

		if (source.isEmpty()) {
			return Optional.empty();
		}

		VideoThumbnailSource value = source.get();

		long version = value.modifiedAt().toEpochSecond(ZoneOffset.UTC);

		String key = publicId + "-" + version + "-video-w" + width;

		Path target = workspaceManager.resolve("cache", "thumbnails", publicId.toString().substring(0, 2),
				key + ".jpg");

		if (!Files.isRegularFile(target)) {
			synchronized (LOCKS[Math.floorMod(key.hashCode(), LOCKS.length)]) {
				if (!Files.isRegularFile(target))
					generate(value, width, target);
			}
		}

		return Optional.of(new PhotoThumbnail(target, '"' + key + '"'));
	}

	private int normalizeWidth(int width) {
		if (width < 1 || width > 640) {
			throw new IllegalArgumentException("Thumbnail width must be between 1 and 640");
		}

		return width <= 320 ? 320 : 640;
	}

	private void generate(VideoThumbnailSource source, int width, Path target) throws IOException {
		Path original = Path.of(source.currentPath()).toAbsolutePath().normalize();

		if (!Files.isRegularFile(original)) {
			throw new IOException("Video source is not available");
		}

		Files.createDirectories(target.getParent());

		Path temporary = Files.createTempFile(target.getParent(), "video-thumbnail-", ".jpg");

		try {
			double seek = source.durationSeconds() == null ? 1D : Math.clamp(source.durationSeconds() * 0.1D, 0D, 10D);

			ffmpegRunner.run(externalToolPaths.ffmpeg(), original, temporary, width, seek);

			if (!Files.isRegularFile(temporary) || Files.size(temporary) == 0) {
				throw new IOException("FFmpeg produced no thumbnail");
			}

			moveAtomically(temporary, target);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();

			throw new IOException("Video thumbnail generation was interrupted", e);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private void moveAtomically(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException _) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}