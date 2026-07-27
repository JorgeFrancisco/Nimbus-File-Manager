package br.com.jorgemelo.nimbusfilemanager.thumbnail.application;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.DecodedPhoto;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.image.PhotoDecoder;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.image.UnsupportedDecodeException;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import br.com.jorgemelo.nimbusfilemanager.thumbnail.application.dto.PhotoThumbnail;
import br.com.jorgemelo.nimbusfilemanager.thumbnail.application.dto.PhotoThumbnailSource;
import br.com.jorgemelo.nimbusfilemanager.thumbnail.infrastructure.persistence.PhotoThumbnailRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class PhotoThumbnailService {

	private static final int[] WIDTHS = { 320, 640 };
	private static final Object[] LOCKS = new Object[64];

	static {
		for (int index = 0; index < LOCKS.length; index++) {
			LOCKS[index] = new Object();
		}
	}

	private final PhotoThumbnailRepository repository;
	private final WorkspaceManager workspaceManager;
	private final PhotoDecoder photoDecoder;

	public PhotoThumbnailService(PhotoThumbnailRepository repository, WorkspaceManager workspaceManager,
			PhotoDecoder photoDecoder) {
		this.repository = repository;
		this.workspaceManager = workspaceManager;
		this.photoDecoder = photoDecoder;
	}

	public Optional<PhotoThumbnail> get(UUID publicId, int requestedWidth) throws IOException {
		int width = normalizeWidth(requestedWidth);

		Optional<PhotoThumbnailSource> source = repository.findSource(publicId);

		if (source.isEmpty()) {
			return Optional.empty();
		}

		PhotoThumbnailSource value = source.get();

		long version = value.modifiedAt().toEpochSecond(ZoneOffset.UTC);

		String key = publicId + "-" + version + "-w" + width;

		Path target = workspaceManager.resolve("cache", "thumbnails", publicId.toString().substring(0, 2),
				key + ".jpg");

		if (!Files.isRegularFile(target)) {
			synchronized (LOCKS[Math.floorMod(key.hashCode(), LOCKS.length)]) {
				if (!Files.isRegularFile(target)) {
					try {
						generate(value, width, target);
					} catch (UnsupportedThumbnailException e) {
						// Expected for undecodable formats (WEBP/HEIC), corrupt images or a
						// source that moved: report "no thumbnail" instead of a 500.
						log.debug("No thumbnail for {}: {}", publicId, e.getMessage());

						return Optional.empty();
					}
				}
			}
		}

		return Optional.of(new PhotoThumbnail(target, '"' + key + '"'));
	}

	/**
	 * The offered size a request rounds up to. Anything outside the offered range
	 * falls through the loop and is refused there, which is also why there is no
	 * return after it: the guard used to live before the loop and left the last
	 * statement unreachable.
	 */
	private int normalizeWidth(int requestedWidth) {
		for (int width : WIDTHS) {
			if (requestedWidth >= 1 && requestedWidth <= width) {
				return width;
			}
		}

		throw new IllegalArgumentException("Thumbnail width must be between 1 and " + WIDTHS[WIDTHS.length - 1]);
	}

	private void generate(PhotoThumbnailSource source, int width, Path target) throws IOException {
		Path original = Path.of(source.currentPath()).toAbsolutePath().normalize();

		if (!Files.isRegularFile(original)) {
			throw new UnsupportedThumbnailException("Photo source is not available");
		}

		BufferedImage oriented;

		try {
			// One shared decoder for hash and thumbnails: WEBP support (via the ImageIO
			// SPI) and the single EXIF/DB-rotation precedence now come for free, ending the
			// old divergence where the thumbnail applied only the persisted rotation.
			DecodedPhoto decoded = photoDecoder.decode(original, source.rotation());

			oriented = decoded.image();
		} catch (UnsupportedDecodeException _) {
			// Format nothing on the classpath can decode (e.g. HEIC): 404, as before.
			throw new UnsupportedThumbnailException("Unsupported photo format");
		} catch (IOException e) {
			// Corrupt/truncated file or a source that vanished: also "no thumbnail" (404).
			throw new UnsupportedThumbnailException("Could not decode photo: " + e.getMessage());
		}

		int targetWidth = Math.min(width, oriented.getWidth());
		int targetHeight = Math.max(1,
				(int) Math.round(oriented.getHeight() * (targetWidth / (double) oriented.getWidth())));

		BufferedImage thumbnail = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);

		Graphics2D graphics = thumbnail.createGraphics();

		try {
			graphics.setColor(Color.WHITE);
			graphics.fillRect(0, 0, targetWidth, targetHeight);
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			graphics.drawImage(oriented, 0, 0, targetWidth, targetHeight, null);
		} finally {
			graphics.dispose();
		}

		Files.createDirectories(target.getParent());

		Path temporary = Files.createTempFile(target.getParent(), "thumbnail-", ".tmp");

		try {
			if (!ImageIO.write(thumbnail, "jpg", temporary.toFile())) {
				throw new IOException("JPEG thumbnail writer is unavailable");
			}

			moveAtomically(temporary, target);
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