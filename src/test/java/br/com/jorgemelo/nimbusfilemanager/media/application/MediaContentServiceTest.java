package br.com.jorgemelo.nimbusfilemanager.media.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.media.application.dto.MediaDetails;
import br.com.jorgemelo.nimbusfilemanager.media.infrastructure.persistence.MediaContentRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;

/**
 * Covers the location label localization the service applies on top of the raw
 * confidence and provider codes the repository returns. Without a Spring
 * context the base pt-BR bundle resolves the labels.
 */
class MediaContentServiceTest {

	private final MediaContentRepository repository = mock(MediaContentRepository.class);

	private final MediaContentService service = new MediaContentService(repository);

	private final UUID id = UUID.fromString("01890000-0000-7000-8000-000000000009");

	@Test
	void findDetailsResolvesLocationLabelsFromRawCodesKeepingTheLevelCode() {
		MediaDetails raw = details("VERY_HIGH", "ADMIN_BOUNDARIES");

		when(repository.findDetails(id)).thenReturn(Optional.of(raw));

		MediaDetails resolved = service.findDetails(id).orElseThrow();

		Assertions.assertThat(resolved.locationConfidence()).isEqualTo("Muito alta");
		Assertions.assertThat(resolved.locationConfidenceLevel()).isEqualTo("VERY_HIGH");
		Assertions.assertThat(resolved.locationSource()).isEqualTo("Limites administrativos");
	}

	@Test
	void findDetailsLeavesLocationLabelsNullWhenCodesAreBlank() {
		MediaDetails raw = details(null, "  ");

		when(repository.findDetails(id)).thenReturn(Optional.of(raw));

		MediaDetails resolved = service.findDetails(id).orElseThrow();

		Assertions.assertThat(resolved.locationConfidence()).isNull();
		Assertions.assertThat(resolved.locationSource()).isNull();
	}

	@Test
	void findDetailsEchoesUnknownCodesUnchanged() {
		MediaDetails raw = details("NOT_A_CONFIDENCE", "NOT_A_PROVIDER");

		when(repository.findDetails(id)).thenReturn(Optional.of(raw));

		MediaDetails resolved = service.findDetails(id).orElseThrow();

		Assertions.assertThat(resolved.locationConfidence()).isEqualTo("NOT_A_CONFIDENCE");
		Assertions.assertThat(resolved.locationSource()).isEqualTo("NOT_A_PROVIDER");
	}

	@Test
	void findDetailsResolvesTypeAndDateSourceLabelsFromTheRawEnums() {
		MediaDetails raw = new MediaDetails(id, "f.mp4", FileType.VIDEO, null, DateSource.EXIF, null, null, null, null,
				null, null, null, null, null, null, null, null, null, null, null, null, null, null);

		when(repository.findDetails(id)).thenReturn(Optional.of(raw));

		MediaDetails resolved = service.findDetails(id).orElseThrow();

		Assertions.assertThat(resolved.typeLabel()).isEqualTo("Vídeo");
		Assertions.assertThat(resolved.dateSourceLabel()).isEqualTo("EXIF");
	}

	@Test
	void findDetailsLeavesTypeAndDateSourceLabelsNullWhenTheEnumsAreAbsent() {
		MediaDetails raw = new MediaDetails(id, "f.bin", null, null, null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, null, null, null, null, null);

		when(repository.findDetails(id)).thenReturn(Optional.of(raw));

		MediaDetails resolved = service.findDetails(id).orElseThrow();

		Assertions.assertThat(resolved.typeLabel()).isNull();
		Assertions.assertThat(resolved.dateSourceLabel()).isNull();
	}

	private MediaDetails details(String confidenceCode, String providerCode) {
		return new MediaDetails(id, "f.jpg", FileType.PHOTO, null, null, null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, confidenceCode, providerCode, null, null);
	}
}