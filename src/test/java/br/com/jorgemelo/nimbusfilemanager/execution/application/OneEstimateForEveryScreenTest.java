package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintRunReader;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.EtaEstimate;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ExecutionLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * One run, two screens, one answer - which is the whole point of the
 * unification.
 *
 * <p>
 * They disagreed before, and not by a little: the duplicates panel derived its
 * own estimate from one counter while the progress screen derived another from a
 * sample window of its own in the browser, over a different counter again. The
 * same fingerprint run could read "less than a minute" on one tab and "3 h 31
 * min" on the other, and finding out which was which meant tracing whether a
 * number had come from Java or from JavaScript.
 */
class OneEstimateForEveryScreenTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.AUGUST, 17, 10, 0);

	private final Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);

	/**
	 * The row a fingerprint drain writes: ten minutes of measured window, a
	 * quarter of the backlog behind it.
	 */
	private final Execution running = Execution.builder().id(1L).executionType(ExecutionType.FINGERPRINT_PHOTO)
			.status(ExecutionStatus.RUNNING).startedAt(NOW.minusHours(2)).totalExpected(1_000).filesFound(250)
			.filesAnalyzed(250).errors(0).rateWindowFromAt(NOW.minusMinutes(10)).rateWindowFromDone(0).build();

	@Test
	void theProgressScreenAndTheDuplicatesPanelReportTheSameRemainingTime() {
		when(executionRepository.findFirstByExecutionTypeAndStatusInOrderByStartedAtDesc(any(), any()))
				.thenReturn(Optional.of(running));

		EtaEstimate fromTheProgressScreen = mapper().toResponse(running).eta();

		EtaEstimate fromTheDuplicatesPanel = new FingerprintRunReader(executionRepository, Progress.estimator(clock))
				.eta(ExecutionType.FINGERPRINT_PHOTO);

		Assertions.assertThat(fromTheProgressScreen).as("two readers, one measurement")
				.isEqualTo(fromTheDuplicatesPanel);
		Assertions.assertThat(fromTheProgressScreen.remainingSeconds()).as("and it is an answer, not a shrug")
				.isEqualTo(1_800);
	}

	/** The percentage is one answer for the same reason, and by the same reader. */
	@Test
	void bothScreensAlsoAgreeAboutHowFarAlongItIs() {
		Assertions.assertThat(mapper().toResponse(running).percentComplete())
				.isEqualTo(Progress.reader().percent(running));
	}

	/**
	 * And the browser is not allowed to work one out for itself.
	 *
	 * <p>
	 * It used to, over a rolling window of its own, and it could not: the field it
	 * sampled means the discovery count in an inventory, the concluded count in a
	 * fingerprint, the total in a similarity analysis and a constant zero in a
	 * metadata rebuild. This asserts the module has no sampling left rather than
	 * trusting that nobody puts it back.
	 */
	@Test
	void theBrowserFormatsTheEstimateAndNeverMeasuresIt() throws IOException {
		String module = Files.readString(Path.of("src/main/resources/static/js/execution-status.js"));

		// The sampling machinery, named rather than guessed at: a clock reading, a
		// window, a sample buffer and the function that divided one by the other. The
		// word "filesFound" still appears in this module, in the comment explaining
		// why it is no longer sampled - prose is not an estimator.
		Assertions.assertThat(module).as("the estimate arrives decided")
				.contains("eta.state")
				.doesNotContain("estimatedRemaining")
				.doesNotContain("Date.now(")
				.doesNotContain("recordSample")
				.doesNotContain("recentFilesPerSecond")
				.doesNotContain("WINDOW_MS")
				.doesNotContain("data.filesFound");

		String progressPage = Files.readString(Path.of("src/main/resources/static/js/pages/execution-progress.js"));

		Assertions.assertThat(progressPage).as("the page renders what the backend decided")
				.contains("executionStatus.eta(data.eta)").doesNotContain("estimatedRemaining");
	}

	private ExecutionMapper mapper() {
		return new ExecutionMapper(mock(ExecutionMessageCodec.class), mock(ExecutionLabels.class), Progress.reader(),
				Progress.estimator(clock));
	}
}