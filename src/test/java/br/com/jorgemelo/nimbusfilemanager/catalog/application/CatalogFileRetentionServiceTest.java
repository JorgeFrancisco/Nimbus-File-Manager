package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.EligibilityAnnouncer;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence.SimilarityPurgeWriter;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.shared.application.catalog.CollectionCatalogMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.CatalogLocationWriter;

@ExtendWith(MockitoExtension.class)
class CatalogFileRetentionServiceTest {

	@Mock
	private CatalogFileRepository catalogFileRepository;

	@Mock
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Mock
	private CatalogLocationWriter catalogLocationWriter;

	@Mock
	private SimilarityPurgeWriter similarityPurgeWriter;

	@Mock
	private EligibilityAnnouncer eligibilityAnnouncer;

	private CatalogFileRetentionService service() {
		return new CatalogFileRetentionService(
				new CollectionCatalogMutations(catalogFileRepository, catalogFileLocationRepository,
						catalogLocationWriter, similarityPurgeWriter, eligibilityAnnouncer),
				Clock.systemDefaultZone());
	}

	@Test
	void purgeUsesCutoffInThePastAndReturnsRemovedCount() {
		when(catalogFileRepository.deleteMissingBefore(any())).thenReturn(4);

		Instant before = Instant.now();

		int removed = service().purgeMissingOlderThan(30, Takings.unfenced(1L)).orElseThrow();

		ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);

		verify(catalogFileRepository).deleteMissingBefore(cutoff.capture());

		Assertions.assertThat(removed).isEqualTo(4);
		Assertions.assertThat(cutoff.getValue()).isBefore(before.minus(Duration.ofDays(29))).isAfter(before.minus(Duration.ofDays(31)));
	}

	/**
	 * A purge changes which files may be analysed, so it says so - through the
	 * same announcement every other mutation of that set uses, never by calling
	 * similarity itself. What it also does is forget what a published analysis
	 * said about files that no longer exist.
	 */
	@Test
	void aPurgeForgetsThePublishedAnalysisAndAsksForAFreshOne() {
		when(catalogFileRepository.deleteMissingBefore(any())).thenReturn(4);

		service().purgeMissingOlderThan(30, Takings.unfenced(1L)).orElseThrow();

		verify(similarityPurgeWriter).forgetPurgedFiles();
		verify(eligibilityAnnouncer).announce("hard purge");
	}

	/** A pass that removed nothing has nothing to announce. */
	@Test
	void aPurgeThatRemovedNothingAnnouncesNothing() {
		when(catalogFileRepository.deleteMissingBefore(any())).thenReturn(0);

		service().purgeMissingOlderThan(30, Takings.unfenced(1L)).orElseThrow();

		verifyNoInteractions(similarityPurgeWriter, eligibilityAnnouncer);
	}

	@Test
	void purgeIsNoOpWhenRetentionIsZero() {
		Assertions.assertThat(service().purgeMissingOlderThan(0, Takings.unfenced(1L)).orElseThrow()).isZero();

		verify(catalogFileRepository, never()).deleteMissingBefore(any());
	}

	@Test
	void purgeIsNoOpWhenRetentionIsNegative() {
		Assertions.assertThat(service().purgeMissingOlderThan(-1, Takings.unfenced(1L)).orElseThrow()).isZero();

		verify(catalogFileRepository, never()).deleteMissingBefore(any());
	}
}