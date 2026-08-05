package br.com.jorgemelo.nimbusfilemanager.worker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.worker.application.constants.WorkerHealthConstants;
import br.com.jorgemelo.nimbusfilemanager.worker.domain.model.WorkerInstance;
import br.com.jorgemelo.nimbusfilemanager.worker.domain.repository.WorkerInstanceRepository;

/**
 * Reading the heartbeat: what counts as alive, and what a screen is told when
 * nothing is.
 */
class WorkerAvailabilityTest {

	private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");

	private final WorkerInstanceRepository workerInstanceRepository = mock(WorkerInstanceRepository.class);

	private final WorkerAvailability workerAvailability = new WorkerAvailability(workerInstanceRepository,
			Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void reportsAWorkerHeardFromWithinTheWindowAsAvailable() {
		WorkerInstance alive = instance("worker-1", now().minusSeconds(5));

		when(workerInstanceRepository.findByLastSeenAtAfter(any())).thenReturn(List.of(alive));
		when(workerInstanceRepository.findAll()).thenReturn(List.of(alive));

		assertThat(workerAvailability.current().available()).isTrue();
		assertThat(workerAvailability.current().instances()).isEqualTo(1);
	}

	/**
	 * The row of a worker that stopped writing is not evidence that it is there.
	 * It stays readable as a date - "nothing since 11:58" is what someone
	 * diagnosing a standstill needs - but it is not counted as an executor.
	 */
	@Test
	void doesNotCountAWorkerWhoseLastBeatIsOlderThanTheWindow() {
		WorkerInstance stale = instance("worker-1", now().minus(WorkerHealthConstants.FRESH_WITHIN).minusSeconds(1));

		when(workerInstanceRepository.findByLastSeenAtAfter(any())).thenReturn(List.of());
		when(workerInstanceRepository.findAll()).thenReturn(List.of(stale));

		assertThat(workerAvailability.current().available()).isFalse();
		assertThat(workerAvailability.current().instances()).isZero();
		assertThat(workerAvailability.current().lastSeenAt()).isEqualTo(stale.getLastSeenAt());
	}

	/**
	 * An installation that never ran a worker is a different problem from one
	 * whose worker died an hour ago, and the answer has to let them be told apart.
	 */
	@Test
	void saysNothingWasEverSeenWhenNoWorkerEverWrote() {
		when(workerInstanceRepository.findByLastSeenAtAfter(any())).thenReturn(List.of());
		when(workerInstanceRepository.findAll()).thenReturn(List.of());

		assertThat(workerAvailability.current().available()).isFalse();
		assertThat(workerAvailability.current().lastSeenAt()).isNull();
	}

	/**
	 * Nimbus starts one worker, so two live rows mean something started another -
	 * by hand, or a supervisor that lost track of its child. Collapsing that into
	 * "yes, available" would hide the only symptom.
	 */
	@Test
	void countsEveryLiveInstanceRatherThanAnsweringYesOrNo() {
		when(workerInstanceRepository.findByLastSeenAtAfter(any()))
				.thenReturn(List.of(instance("worker-1", now()), instance("worker-2", now())));
		when(workerInstanceRepository.findAll()).thenReturn(List.of(instance("worker-1", now())));

		assertThat(workerAvailability.current().instances()).isEqualTo(2);
	}

	private LocalDateTime now() {
		return LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
	}

	private WorkerInstance instance(String workerId, LocalDateTime lastSeenAt) {
		return WorkerInstance.builder().workerId(workerId).lastSeenAt(lastSeenAt).build();
	}
}