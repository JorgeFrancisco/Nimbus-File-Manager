package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.constants.ConversionConstants;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionCandidateView;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.repository.ConversionCandidateRepository;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.repository.projection.ConversionCandidate;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;

class ConversionCandidateServiceTest {

	private final ConversionCandidateRepository repository = mock(ConversionCandidateRepository.class);
	private final ConversionCandidateService service = new ConversionCandidateService(repository);

	private final UUID publicId = UUID.randomUUID();

	@Test
	void describesEachCandidateReadyToRender() {
		stub(new ConversionCandidate(publicId, "clip.mp4", "D:\\library\\clip.mp4", "D:\\library", 1_048_576L, "mp4",
				"h264", 3_849.0, 1920, 1080));

		ConversionCandidateView view = service.candidates(PageRequest.of(0, 25)).getContent().getFirst();

		Assertions.assertThat(view.mediaPublicId()).isEqualTo(publicId);
		Assertions.assertThat(view.name()).isEqualTo("clip.mp4");
		Assertions.assertThat(view.folder()).isEqualTo("D:\\library");
		Assertions.assertThat(view.sizeBytes()).isEqualTo(1_048_576L);
		Assertions.assertThat(view.sizeLabel()).isNotBlank();
		Assertions.assertThat(view.codecLabel()).isEqualTo("H264");
		Assertions.assertThat(view.containerLabel()).isEqualTo("MP4");
		Assertions.assertThat(view.durationLabel()).isEqualTo("1:04:09");
		Assertions.assertThat(view.resolutionLabel()).isEqualTo("1920 × 1080");
	}

	@Test
	void describesEachCandidateAsTheSharedMediaCardExpects() {
		stub(candidate(10.0));

		ConversionCandidateView view = service.candidates(PageRequest.of(0, 25)).getContent().getFirst();

		// The card renders its video branch, which is what gives this screen the same
		// thumbnail and lightbox player the other media screens have.
		Assertions.assertThat(view.video()).isTrue();
		Assertions.assertThat(view.image()).isFalse();
		Assertions.assertThat(view.pdf()).isFalse();
		Assertions.assertThat(view.text()).isFalse();
		Assertions.assertThat(view.audio()).isFalse();
		Assertions.assertThat(view.previewUrl()).isEqualTo("/api/media/" + publicId + "/content");
		Assertions.assertThat(view.iconClass()).isNotBlank();
		Assertions.assertThat(view.iconLabelKey()).isNotBlank();
	}

	@Test
	void formatsShortDurationsWithoutAnHourPart() {
		stub(candidate(249.0));

		Assertions.assertThat(service.candidates(PageRequest.of(0, 25)).getContent().getFirst().durationLabel())
				.isEqualTo("4:09");
	}

	@Test
	void fallsBackToAPlaceholderWhenTheStreamFactsAreMissing() {
		stub(new ConversionCandidate(publicId, "clip.mp4", null, null, null, null, null, null, null, null));

		ConversionCandidateView view = service.candidates(PageRequest.of(0, 25)).getContent().getFirst();

		Assertions.assertThat(view.sizeBytes()).isZero();
		Assertions.assertThat(view.codecLabel()).isEqualTo("Desconhecido");
		Assertions.assertThat(view.containerLabel()).isEqualTo("—");
		Assertions.assertThat(view.durationLabel()).isEqualTo("—");
		Assertions.assertThat(view.resolutionLabel()).isEqualTo("—");
	}

	@Test
	void treatsAZeroDurationAsUnknownRatherThanShowingZero() {
		stub(candidate(0.0));

		Assertions.assertThat(service.candidates(PageRequest.of(0, 25)).getContent().getFirst().durationLabel())
				.isEqualTo("—");
	}

	@Test
	void asksOnlyForActiveVideosThatAreNotAnHevcMp4Yet() {
		stub(candidate(10.0));

		service.candidates(PageRequest.of(0, 25));

		verify(repository).findCandidates(eq(FileType.VIDEO), eq(LifecycleStatus.ACTIVE),
				eq(ConversionConstants.OUTPUT_EXTENSION), eq(ConversionConstants.HEVC_CODECS), any());
	}

	private ConversionCandidate candidate(Double durationSeconds) {
		return new ConversionCandidate(publicId, "clip.mp4", "D:\\library\\clip.mp4", "D:\\library", 10L, "mp4", "h264",
				durationSeconds, 1920, 1080);
	}

	private void stub(ConversionCandidate candidate) {
		when(repository.findCandidates(any(), any(), any(), any(), any()))
				.thenReturn(new PageImpl<>(List.of(candidate)));
	}
}