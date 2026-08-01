package br.com.jorgemelo.nimbusfilemanager.shared.domain.repository;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.ExecutionTelemetryRow;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.model.ExecutionMetrics;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.repository.ExecutionMetricsRepository;

/**
 * Validates the telemetry queries against a real Postgres after
 * {@code ExecutionMetrics} became unidirectional: they now join
 * {@code execution -> execution_metrics} via an explicit entity join
 * ({@code JOIN ExecutionMetrics m ON m.id = e.id}) instead of navigating the
 * removed {@code Execution.metrics} field. Covers the tricky parts a mock
 * cannot: the INNER-join queries exclude executions without a metrics row,
 * while the LEFT-join lookups still return those rows with null metrics
 * columns.
 */
@SpringBootTest
@Transactional
@Testcontainers
class ExecutionTelemetryQueryIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private ExecutionMetricsRepository executionMetricsRepository;

	@Test
	void telemetryQueriesJoinMetricsAndHandleExecutionsWithout() {
		LocalDateTime base = LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0, 0);

		Long measuredOld = measured("1.0.0.1", base, 1_000L, 4).getId();
		Long measuredNew = measured("2.0.0.2", base.plusMinutes(1), 2_000L, 8).getId();
		Long unmeasured = execution("3.0.0.3", base.plusMinutes(2)).getId();

		// INNER JOIN: only executions with a metrics row (durationMillis not null),
		// most recent first; the unmeasured execution is excluded.
		List<ExecutionTelemetryRow> recent = executionRepository.findTelemetry(null, PageRequest.of(0, 10));

		Assertions.assertThat(recent).extracting(ExecutionTelemetryRow::id).containsExactly(measuredNew, measuredOld);
		Assertions.assertThat(recent).noneMatch(row -> row.id().equals(unmeasured));
		Assertions.assertThat(recent.getFirst().durationMillis()).isEqualTo(2_000L);
		Assertions.assertThat(recent.getFirst().workers()).isEqualTo(8);

		// Version filter.
		Assertions.assertThat(executionRepository.findTelemetry("2.0.0.2", PageRequest.of(0, 10)))
				.extracting(ExecutionTelemetryRow::id).containsExactly(measuredNew);

		// LEFT JOIN by id: measured row has metrics, unmeasured row is still returned
		// with null metrics columns.
		Assertions.assertThat(executionRepository.findTelemetryById(measuredOld)).get()
				.extracting(ExecutionTelemetryRow::durationMillis).isEqualTo(1_000L);
		Assertions.assertThat(executionRepository.findTelemetryById(unmeasured)).get().satisfies(row -> {
			Assertions.assertThat(row.id()).isEqualTo(unmeasured);
			Assertions.assertThat(row.durationMillis()).isNull();
			Assertions.assertThat(row.applicationVersion()).isEqualTo("3.0.0.3");
		});

		// LEFT JOIN by ids: all three returned, most recent first, unmeasured with null
		// metrics.
		Assertions.assertThat(executionRepository.findTelemetryByIds(List.of(measuredOld, measuredNew, unmeasured)))
				.extracting(ExecutionTelemetryRow::id).containsExactly(unmeasured, measuredNew, measuredOld);

		// Distinct versions with a measured metrics row, most recent first; the
		// unmeasured version is absent.
		Assertions.assertThat(executionRepository.findTelemetryVersions()).containsExactly("2.0.0.2", "1.0.0.1")
				.doesNotContain("3.0.0.3");
	}

	private Execution measured(String version, LocalDateTime startedAt, long durationMillis, int workers) {
		Execution execution = execution(version, startedAt);

		executionMetricsRepository.saveAndFlush(ExecutionMetrics.builder().execution(execution)
				.durationMillis(durationMillis).filesPerSecond(10.0).workers(workers).chunkSize(200).build());

		return execution;
	}

	private Execution execution(String version, LocalDateTime startedAt) {
		return executionRepository.saveAndFlush(
				Execution.builder().executionType(ExecutionType.INVENTORY).status(ExecutionStatus.FINISHED)
						.startedAt(startedAt).finishedAt(startedAt.plusSeconds(1)).applicationVersion(version).build());
	}
}