package br.com.jorgemelo.nimbusfilemanager.worker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.worker.application.constants.WorkerHealthConstants;
import br.com.jorgemelo.nimbusfilemanager.worker.domain.model.WorkerInstance;
import br.com.jorgemelo.nimbusfilemanager.worker.domain.repository.WorkerInstanceRepository;

/**
 * Saying "I am here": one row, written under this worker's own id, and nothing
 * else touched.
 */
class WorkerHeartbeatTest {

	private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");

	private final WorkerInstanceRepository workerInstanceRepository = mock(WorkerInstanceRepository.class);
	private final WorkerIdentity workerIdentity = mock(WorkerIdentity.class);

	private final WorkerHeartbeat workerHeartbeat = new WorkerHeartbeat(workerInstanceRepository, workerIdentity,
			Clock.fixed(NOW, ZoneOffset.UTC));

	@AfterEach
	void stop() {
		workerHeartbeat.stop();
	}

	/**
	 * The first beat is written by start() itself rather than by the schedule, so
	 * a worker that has just been told to claim is already visible. Waiting one
	 * interval would leave a window where work is claimed by a worker the
	 * application reports as absent.
	 */
	@Test
	void writesItsFirstBeatBeforeTheScheduleEverRuns() {
		when(workerIdentity.workerId()).thenReturn("worker-42-1000");

		workerHeartbeat.start();

		ArgumentCaptor<WorkerInstance> written = ArgumentCaptor.forClass(WorkerInstance.class);

		verify(workerInstanceRepository).save(written.capture());

		assertThat(written.getValue().getWorkerId()).isEqualTo("worker-42-1000");
		assertThat(written.getValue().getLastSeenAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
	}

	/**
	 * Every restart is a new id, so the rows of workers long gone are cleared by
	 * whoever arrives - the one process certain to be alive at that moment.
	 */
	@Test
	void clearsTheRowsOfWorkersNobodyHasSeenInDaysWhenItArrives() {
		when(workerIdentity.workerId()).thenReturn("worker-42-1000");

		workerHeartbeat.start();

		verify(workerInstanceRepository).deleteByLastSeenAtBefore(
				LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minus(WorkerHealthConstants.FORGET_AFTER));
	}

	/**
	 * Losing the database is already fatal to everything else the worker does, and
	 * a heartbeat that propagated the failure would take the process down over the
	 * one thing that only reports on it.
	 */
	@Test
	void survivesARoundThatCouldNotReachTheDatabase() {
		when(workerIdentity.workerId()).thenReturn("worker-42-1000");
		when(workerInstanceRepository.save(any())).thenThrow(new IllegalStateException("no connection"));

		assertThatCode(workerHeartbeat::start).doesNotThrowAnyException();
	}
}