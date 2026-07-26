package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.LongConsumer;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildRequest;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildResponse;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MetadataRebuildField;

/**
 * Background rebuild runner: single-flight start guard, candidate counting,
 * progress reporting and the running-flag lifecycle.
 */
class MetadataRebuildAsyncRunnerTest {

	private final MetadataRebuildService metadataRebuildService = mock(MetadataRebuildService.class);
	private final MetadataRebuildAsyncRunner runner = new MetadataRebuildAsyncRunner(metadataRebuildService);

	private final MetadataRebuildRequest request = request(false);

	@Test
	void startCountsCandidatesAndMarksRunning() {
		when(metadataRebuildService.countCandidates(request)).thenReturn(42L);

		Assertions.assertThat(runner.start(request)).isTrue();
		Assertions.assertThat(runner.isRunning()).isTrue();
		Assertions.assertThat(runner.total()).isEqualTo(42);
		Assertions.assertThat(runner.processed()).isZero();
	}

	@Test
	void startReturnsFalseWhenAlreadyRunning() {
		when(metadataRebuildService.countCandidates(any())).thenReturn(1L);

		Assertions.assertThat(runner.start(request)).isTrue();
		Assertions.assertThat(runner.start(request)).isFalse();
	}

	/**
	 * An unreadable total only costs the percentage and the estimate, so the
	 * rebuild must still be allowed to start.
	 */
	@Test
	void startTreatsCountingFailureAsZeroTotal() {
		when(metadataRebuildService.countCandidates(any())).thenThrow(new RuntimeException("db down"));

		Assertions.assertThat(runner.start(request)).isTrue();
		Assertions.assertThat(runner.total()).isZero();
	}

	@Test
	void rebuildStoresResultAndClearsRunningFlag() {
		MetadataRebuildResponse result = new MetadataRebuildResponse("D:\\photos", false, 5, 4, 1, 0, 0, 0);

		when(metadataRebuildService.countCandidates(any())).thenReturn(5L);
		when(metadataRebuildService.rebuild(eq(request), any())).thenReturn(result);

		runner.start(request);
		runner.rebuild(request);

		Assertions.assertThat(runner.lastResult()).isSameAs(result);
		Assertions.assertThat(runner.lastError()).isNull();
		Assertions.assertThat(runner.isRunning()).isFalse();
	}

	@Test
	void rebuildRecordsErrorMessageOnFailure() {
		when(metadataRebuildService.rebuild(any(), any())).thenThrow(new RuntimeException("boom"));

		runner.rebuild(request);

		Assertions.assertThat(runner.lastError()).isEqualTo("boom");
		Assertions.assertThat(runner.isRunning()).isFalse();
	}

	@Test
	void rebuildPublishesTheProgressTheServiceReports() {
		when(metadataRebuildService.countCandidates(any())).thenReturn(10L);
		when(metadataRebuildService.rebuild(eq(request), any())).thenAnswer(invocation -> {
			invocation.getArgument(1, LongConsumer.class).accept(7L);

			return new MetadataRebuildResponse("D:\\photos", false, 10, 7, 3, 0, 0, 0);
		});

		runner.start(request);
		runner.rebuild(request);

		Assertions.assertThat(runner.processed()).isEqualTo(7);
		Assertions.assertThat(runner.percent()).isEqualTo(70);
	}

	@Test
	void progressAndEtaAreUnknownWhileThereIsNoTotal() {
		when(metadataRebuildService.countCandidates(any())).thenReturn(0L);

		runner.start(request);

		// total 0 -> percentage/eta are "unknown" (-1) per ProgressMath.
		Assertions.assertThat(runner.percent()).isEqualTo(-1);
		Assertions.assertThat(runner.etaSeconds()).isEqualTo(-1);
	}

	private MetadataRebuildRequest request(boolean dryRun) {
		return new MetadataRebuildRequest("D:\\photos", List.of(MetadataRebuildField.SUBCATEGORY), null, null, null,
				dryRun);
	}
}