package br.com.jorgemelo.nimbusfilemanager.media.application;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationProvider;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.ByteRange;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.MediaContentResponse;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.MediaContentSource;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.MediaDetails;
import br.com.jorgemelo.nimbusfilemanager.media.infrastructure.persistence.MediaContentRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationConfidence;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

@Service
public class MediaContentService extends LocalizedComponent {

	private final MediaContentRepository repository;

	public MediaContentService(MediaContentRepository repository) {
		this.repository = repository;
	}

	public Optional<MediaDetails> findDetails(UUID publicId) {
		return repository.findDetails(publicId).map(this::withLocalizedLocationLabels);
	}

	public Optional<MediaContentResponse> prepare(UUID publicId, String range) throws IOException {
		Optional<MediaContentSource> sourceOptional = repository.findContent(publicId);

		if (sourceOptional.isEmpty()) {
			return Optional.empty();
		}

		MediaContentSource source = sourceOptional.get();

		Path file = Path.of(source.currentPath()).toAbsolutePath().normalize();

		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}

		long totalLength = Files.size(file);

		ByteRange byteRange = parseRange(range, totalLength);

		return Optional.of(new MediaContentResponse(file, totalLength, byteRange, safeName(source.fileName()),
				contentType(source, file)));
	}

	public void stream(MediaContentResponse content, OutputStream outputStream) throws IOException {
		stream(content.file(), outputStream, content.byteRange());
	}

	ByteRange parseRange(String header, long length) {
		if (header == null || !header.startsWith("bytes=") || header.contains(",")) {
			return new ByteRange(0, length - 1, false);
		}

		try {
			String[] parts = header.substring(6).split("-", -1);

			long start = Long.parseLong(parts[0]);
			long end = parts.length < 2 || parts[1].isBlank() ? length - 1 : Long.parseLong(parts[1]);

			if (start < 0 || start >= length || end < start) {
				throw new IllegalArgumentException("Invalid media byte range");
			}

			return new ByteRange(start, Math.min(end, length - 1), true);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid media byte range", e);
		}
	}

	private void stream(Path file, OutputStream output, ByteRange range) throws IOException {
		try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
			input.seek(range.start());

			byte[] buffer = new byte[64 * 1024];

			long remaining = range.length();

			while (remaining > 0) {
				int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));

				if (read < 0) {
					break;
				}

				output.write(buffer, 0, read);

				remaining -= read;
			}
		}
	}

	private String contentType(MediaContentSource source, Path file) throws IOException {
		String value = source.mimeType();

		if (value == null || value.isBlank()) {
			value = Files.probeContentType(file);
		}

		return value == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : value;
	}

	String safeName(String name) {
		return name.replace("\"", "").replace("\r", "").replace("\n", "");
	}

	private MediaDetails withLocalizedLocationLabels(MediaDetails details) {
		return new MediaDetails(details.id(), details.fileName(), details.type(), details.captureDate(),
				details.dateSource(), details.createdAt(), details.modifiedAt(), details.width(), details.height(),
				details.manufacturer(), details.model(), details.latitude(), details.longitude(),
				details.durationSeconds(), details.currentPath(), details.contentUrl(), details.location(),
				details.locationDistanceKm(), confidenceLabel(details.locationConfidenceLevel()),
				details.locationConfidenceLevel(), providerLabel(details.locationSource()), typeLabel(details.type()),
				dateSourceLabel(details.dateSource()));
	}

	private String typeLabel(FileType type) {
		return type == null ? null : message("enum.fileType." + type.name());
	}

	private String dateSourceLabel(DateSource dateSource) {
		return dateSource == null ? null : message("enum.dateSource." + dateSource.name());
	}

	private String confidenceLabel(String code) {
		if (code == null || code.isBlank()) {
			return null;
		}

		try {
			return message("enum.locationConfidence." + LocationConfidence.valueOf(code).name());
		} catch (IllegalArgumentException _) {
			return code;
		}
	}

	private String providerLabel(String code) {
		if (code == null || code.isBlank()) {
			return null;
		}

		try {
			return message("enum.locationProvider." + LocationProvider.valueOf(code).name());
		} catch (IllegalArgumentException _) {
			return code;
		}
	}
}