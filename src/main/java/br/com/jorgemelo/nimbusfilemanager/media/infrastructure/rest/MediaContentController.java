package br.com.jorgemelo.nimbusfilemanager.media.infrastructure.rest;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.jorgemelo.nimbusfilemanager.media.application.MediaContentService;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.ByteRange;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.MediaContentResponse;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.MediaDetails;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/media")
public class MediaContentController {

	private final MediaContentService contentService;

	public MediaContentController(MediaContentService contentService) {
		this.contentService = contentService;
	}

	@GetMapping("/{publicId}")
	@Operation(summary = "Returns media details")
	public ResponseEntity<MediaDetails> details(@PathVariable UUID publicId) {
		return ResponseEntity.of(contentService.findDetails(publicId));
	}

	@GetMapping("/{publicId}/content")
	@Operation(summary = "Streams an original inventoried file, including HTTP byte ranges")
	public void content(@PathVariable UUID publicId,
			@RequestHeader(name = HttpHeaders.RANGE, required = false) String range, HttpServletResponse response)
			throws IOException {
		Optional<MediaContentResponse> contentOptional = contentService.prepare(publicId, range);

		if (contentOptional.isEmpty()) {
			response.sendError(HttpStatus.NOT_FOUND.value());

			return;
		}

		MediaContentResponse content = contentOptional.get();

		applyHeaders(response, content);

		contentService.stream(content, response.getOutputStream());
	}

	private void applyHeaders(HttpServletResponse response, MediaContentResponse content) {
		ByteRange range = content.byteRange();

		response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
		response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + content.fileName() + "\"");
		response.setContentType(content.contentType());
		response.setStatus(range.partial() ? HttpStatus.PARTIAL_CONTENT.value() : HttpStatus.OK.value());
		response.setHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(range.length()));

		if (range.partial()) {
			response.setHeader(HttpHeaders.CONTENT_RANGE,
					"bytes " + range.start() + "-" + range.end() + "/" + content.totalLength());
		}
	}
}