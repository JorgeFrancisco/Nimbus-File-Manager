package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

@ExtendWith(MockitoExtension.class)
class ExecutionRetentionServiceTest {

	@Mock
	private ExecutionRepository executionRepository;

	private ExecutionRetentionService service() {
		return new ExecutionRetentionService(executionRepository, Clock.systemDefaultZone());
	}

	@Test
	void deleteOlderThanDaysUsesCutoffInThePast() {
		when(executionRepository.deleteFinishedBefore(any(), any())).thenReturn(4);

		LocalDateTime before = LocalDateTime.now();

		int removed = service().deleteOlderThanDays(30);

		ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);

		verify(executionRepository).deleteFinishedBefore(cutoff.capture(), any());

		Assertions.assertThat(removed).isEqualTo(4);
		Assertions.assertThat(cutoff.getValue()).isBefore(before.minusDays(29)).isAfter(before.minusDays(31));
	}

	@Test
	void deleteOlderThanZeroDaysStillDeletesFinishedBeforeNow() {
		when(executionRepository.deleteFinishedBefore(any(), any())).thenReturn(2);

		Assertions.assertThat(service().deleteOlderThanDays(0)).isEqualTo(2);
	}

	@Test
	void deleteOlderThanRejectsNegativeDays() {
		Assertions.assertThatIllegalArgumentException().isThrownBy(() -> service().deleteOlderThanDays(-1));

		verify(executionRepository, never()).deleteFinishedBefore(any(), any());
	}

	@Test
	void keepLatestZeroDeletesAllFinished() {
		when(executionRepository.deleteAllFinished(any())).thenReturn(7);

		Assertions.assertThat(service().keepLatest(0)).isEqualTo(7);

		verify(executionRepository, never()).findFinishedIdsByStartedAtDesc(any());
	}

	@Test
	void keepLatestDeletesEverythingExceptTheMostRecentIds() {
		when(executionRepository.findFinishedIdsByStartedAtDesc(any(Pageable.class))).thenReturn(List.of(10L, 9L, 8L));
		when(executionRepository.deleteFinishedNotIn(eq(List.of(10L, 9L, 8L)), any())).thenReturn(5);

		Assertions.assertThat(service().keepLatest(3)).isEqualTo(5);
	}

	@Test
	void keepLatestDoesNothingWhenThereAreNoFinishedExecutions() {
		when(executionRepository.findFinishedIdsByStartedAtDesc(any(Pageable.class))).thenReturn(List.of());

		Assertions.assertThat(service().keepLatest(3)).isZero();

		verify(executionRepository, never()).deleteFinishedNotIn(any(), any());
	}

	@Test
	void keepLatestRejectsNegativeCount() {
		Assertions.assertThatIllegalArgumentException().isThrownBy(() -> service().keepLatest(-1));

		verify(executionRepository, never()).deleteAllFinished(any());
		verify(executionRepository, never()).findFinishedIdsByStartedAtDesc(any());
	}
}