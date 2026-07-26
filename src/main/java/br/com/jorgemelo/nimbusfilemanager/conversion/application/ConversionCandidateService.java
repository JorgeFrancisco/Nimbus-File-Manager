package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.constants.ConversionConstants;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionCandidateView;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.repository.ConversionCandidateRepository;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.repository.projection.ConversionCandidate;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.FileTypeIcon;
import br.com.jorgemelo.nimbusfilemanager.shared.util.SizeFormatter;

/**
 * The read side of the Conversão screen: which videos are worth converting,
 * already described in finished text. Every label is resolved here so the
 * template never formats a size, a duration or an unknown codec itself.
 */
@Service
public class ConversionCandidateService extends LocalizedComponent {

	private static final int SECONDS_PER_MINUTE = 60;
	private static final int SECONDS_PER_HOUR = 3_600;

	private final ConversionCandidateRepository conversionCandidateRepository;

	public ConversionCandidateService(ConversionCandidateRepository conversionCandidateRepository) {
		this.conversionCandidateRepository = conversionCandidateRepository;
	}

	@Transactional(readOnly = true)
	public Page<ConversionCandidateView> candidates(Pageable pageable) {
		return conversionCandidateRepository.findCandidates(FileType.VIDEO, LifecycleStatus.ACTIVE,
				ConversionConstants.OUTPUT_EXTENSION, ConversionConstants.HEVC_CODECS, pageable).map(this::toView);
	}

	private ConversionCandidateView toView(ConversionCandidate candidate) {
		long sizeBytes = candidate.sizeBytes() == null ? 0 : candidate.sizeBytes();

		// The kind flags are constants here, not a lookup: the query only ever returns
		// videos, so the shared media card always renders its video branch - thumbnail
		// plus the same lightbox player the other screens use.
		return new ConversionCandidateView(candidate.publicId(), candidate.fileName(), candidate.currentFolder(),
				candidate.currentPath(), sizeBytes, SizeFormatter.format(sizeBytes), codecLabel(candidate.videoCodec()),
				containerLabel(candidate.extension()), durationLabel(candidate.durationSeconds()),
				resolutionLabel(candidate.width(), candidate.height()), false, true, false, false, false,
				contentUrl(candidate), FileTypeIcon.iconClass(FileType.VIDEO.name()),
				FileTypeIcon.iconLabelKey(FileType.VIDEO.name()));
	}

	private String codecLabel(String videoCodec) {
		return videoCodec == null || videoCodec.isBlank() ? message("backend.conversion.codecUnknown")
				: videoCodec.trim().toUpperCase(Locale.ROOT);
	}

	private String containerLabel(String extension) {
		return extension == null || extension.isBlank() ? "—" : extension.trim().toUpperCase(Locale.ROOT);
	}

	/** {@code 1:04:09} for anything over an hour, {@code 4:09} otherwise. */
	private String durationLabel(Double durationSeconds) {
		if (durationSeconds == null || durationSeconds <= 0) {
			return "—";
		}

		long total = Math.round(durationSeconds);

		long hours = total / SECONDS_PER_HOUR;
		long minutes = total % SECONDS_PER_HOUR / SECONDS_PER_MINUTE;
		long seconds = total % SECONDS_PER_MINUTE;

		return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
				: String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
	}

	private String resolutionLabel(Integer width, Integer height) {
		return width == null || height == null ? "—" : width + " × " + height;
	}

	/**
	 * The lightbox source. The card derives the thumbnail URL from the public id on
	 * its own, so only the content URL has to be handed over.
	 */
	private String contentUrl(ConversionCandidate candidate) {
		return "/api/media/" + candidate.publicId() + "/content";
	}
}