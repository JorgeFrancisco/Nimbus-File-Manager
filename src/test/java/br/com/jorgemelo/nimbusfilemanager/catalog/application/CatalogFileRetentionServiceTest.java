package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.shared.application.catalog.CollectionCatalogMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

@ExtendWith(MockitoExtension.class)
class CatalogFileRetentionServiceTest {

	@Mock
	private CatalogFileRepository catalogFileRepository;

	@Mock
	private CatalogFileLocationRepository catalogFileLocationRepository;

	private CatalogFileRetentionService service() {
		return new CatalogFileRetentionService(
				new CollectionCatalogMutations(catalogFileRepository, catalogFileLocationRepository),
				Clock.systemDefaultZone());
	}

	@Test
	void purgeUsesCutoffInThePastAndReturnsRemovedCount() {
		when(catalogFileRepository.deleteMissingBefore(any())).thenReturn(4);

		LocalDateTime before = LocalDateTime.now();

		int removed = service().purgeMissingOlderThan(30, Takings.unfenced(1L)).orElseThrow();

		ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);

		verify(catalogFileRepository).deleteMissingBefore(cutoff.capture());

		Assertions.assertThat(removed).isEqualTo(4);
		Assertions.assertThat(cutoff.getValue()).isBefore(before.minusDays(29)).isAfter(before.minusDays(31));
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