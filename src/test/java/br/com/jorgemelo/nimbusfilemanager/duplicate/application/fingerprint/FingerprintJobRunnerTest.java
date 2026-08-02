package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.LoggerFactory;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DrainResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintJobStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.FingerprintJobRun;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintJobRunRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.application.BackgroundWorkGate;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * What the neutral driver does when a run does not go to plan: it yields to an
 * inventory, it survives its own bookkeeping failing, and it tells a genuine
 * defect apart from the application being pulled out from under it.
 *
 * <p>
 * That last one is why this class exists. The first restore run against a real
 * catalog printed a stack trace for every backlog that happened to be querying
 * while {@code pg_restore} was dropping its tables - which reads as a broken
 * installation to whoever opens the log, though nothing was wrong.
 *
 * <p>
 * Isolated because it swaps the level of a process-global logger, which a test
 * running beside it would see.
 */
@Isolated
class FingerprintJobRunnerTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-02T09:00:00Z"), ZoneOffset.UTC);
	private static final long RUN_ID = 7L;

	private final FingerprintBacklog backlog = mock(FingerprintBacklog.class);
	private final FingerprintJobRunRepository jobRunRepository = mock(FingerprintJobRunRepository.class);
	private final BackgroundWorkGate gate = new BackgroundWorkGate();
	private final FingerprintJobRun runRecord = new FingerprintJobRun();

	private final FingerprintJobRunner runner = new FingerprintJobRunner(backlog, jobRunRepository, CLOCK, gate);

	/** An inventory owns the files; a rebuild would fight it for every one. */
	@Test
	void refusesToRebuildWhileAnInventoryIsRunning() {
		when(backlog.pausedByActiveExecution()).thenReturn(true);

		Assertions.assertThat(runner.prepareRebuild()).isFalse();

		verify(backlog, never()).rebuild();
	}

	/**
	 * An inventory that starts mid-drain ends the run, and the record has to say
	 * cancelled rather than finished - the backlog is still there.
	 */
	@Test
	void recordsARunAnInventoryInterruptedAsCancelled() {
		started();

		when(backlog.drainPending(any(), any())).thenReturn(new DrainResult(3, 0));
		when(backlog.pausedByActiveExecution()).thenReturn(false, true);

		runner.start();
		runner.run();

		Assertions.assertThat(runRecord.getStatus()).isEqualTo(FingerprintJobStatus.CANCELLED);
		Assertions.assertThat(runner.isRunning()).isFalse();
	}

	/**
	 * A shutdown or a restore leaves the same mark a broken query would, and only
	 * the gate tells them apart. The run is still recorded as failed; what it must
	 * not do is claim something needs investigating.
	 */
	@Test
	void keepsAFailureCausedByARestoreOutOfTheErrorLog() {
		started();

		when(backlog.drainPending(any(), any()))
				.thenThrow(new IllegalStateException("relation \"media_fingerprint\" does not exist"));

		gate.restoreStarted();

		runner.start();

		Assertions.assertThat(levelsLoggedBy(runner::run)).doesNotContain(Level.ERROR).contains(Level.DEBUG);

		Assertions.assertThat(runRecord.getStatus()).isEqualTo(FingerprintJobStatus.FAILED);
		Assertions.assertThat(runner.lastError()).contains("media_fingerprint");
	}

	/** Nothing gated it, so the same failure is the defect it looks like. */
	@Test
	void reportsAGenuineFailureAsAnError() {
		started();

		when(backlog.drainPending(any(), any())).thenThrow(new IllegalStateException("no such column"));

		runner.start();

		Assertions.assertThat(levelsLoggedBy(runner::run)).contains(Level.ERROR);
	}

	/**
	 * The bookkeeping is not the job. A run record that cannot be written back -
	 * the same restore, one moment later - must not turn a finished drain into an
	 * exception thrown on a thread with nobody to catch it.
	 */
	@Test
	void finishesEvenWhenTheRunRecordCannotBeWrittenBack() {
		started();

		when(backlog.drainPending(any(), any())).thenReturn(new DrainResult(1, 0));
		when(jobRunRepository.findById(RUN_ID)).thenThrow(new IllegalStateException("connection is closed"));

		runner.start();

		Assertions.assertThatNoException().isThrownBy(runner::run);

		Assertions.assertThat(runner.isRunning()).isFalse();
	}

	/**
	 * A drain that never went through {@code start()} has no record to close, and
	 * inventing one would report a run nobody asked for.
	 */
	@Test
	void writesNoRecordForADrainThatWasNeverStarted() {
		when(backlog.drainPending(any(), any())).thenReturn(new DrainResult(0, 0));

		runner.run();

		verify(jobRunRepository, never()).save(any());
	}

	/** Leaves the repository answering the way a started run needs it to. */
	private void started() {
		when(backlog.status()).thenReturn(new FingerprintBacklogStatus(5, 0, 0));
		when(jobRunRepository.save(any())).thenAnswer(call -> {
			FingerprintJobRun saved = call.getArgument(0);

			saved.setId(RUN_ID);

			return saved;
		});
		when(jobRunRepository.findById(RUN_ID)).thenReturn(Optional.of(runRecord));
	}

	/**
	 * The levels the runner logged while the action ran. The level is the contract
	 * being asserted: a situation the application itself caused is not an error,
	 * and the log is the only place that decision shows.
	 */
	private List<Level> levelsLoggedBy(Runnable action) {
		Logger logger = (Logger) LoggerFactory.getLogger(FingerprintJobRunner.class);

		Level original = logger.getLevel();

		ListAppender<ILoggingEvent> appender = new ListAppender<>();

		appender.start();

		logger.setLevel(Level.DEBUG);
		logger.addAppender(appender);

		try {
			action.run();
		} finally {
			logger.detachAppender(appender);
			logger.setLevel(original);
		}

		return appender.list.stream().map(ILoggingEvent::getLevel).toList();
	}
}