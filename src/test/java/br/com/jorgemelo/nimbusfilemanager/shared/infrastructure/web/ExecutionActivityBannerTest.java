package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Guards the one banner that reports everything in flight. What is asserted
 * here is structure a Java test can see, because the properties that broke
 * before were structural: two pollers instead of one, a poller that stopped
 * asking, a banner that rendered as an empty bar.
 */
class ExecutionActivityBannerTest {

	private static final Path SCRIPTS = Path.of("src/main/resources/static/js");
	private static final Path BANNER = SCRIPTS.resolve("execution-activity.js");

	/**
	 * One poller, and it is this file. Two banners polling two endpoints is the
	 * arrangement this replaced: they could each be right about their own question
	 * and still leave the user unable to see what the machine was doing.
	 */
	@Test
	void exactlyOneScriptAsksTheActivityEndpoint() throws Exception {
		List<Path> askers;

		try (Stream<Path> scripts = Files.walk(SCRIPTS)) {
			askers = scripts.filter(path -> path.toString().endsWith(".js")).filter(this::asksForActivity).toList();
		}

		assertThat(askers).containsExactly(BANNER);
	}

	/**
	 * The banner declares {@code display: grid}, and a declared display beats the
	 * browser's own rule for [hidden]. Without the explicit override it rendered as
	 * an empty bar whenever nothing was running - which is most of the time.
	 */
	@Test
	void theBannerIsActuallyHiddenWhenNothingIsHappening() throws Exception {
		String css = Files.readString(Path.of("src/main/resources/static/css/layout.css"));

		assertThat(css).contains(".active-execution[hidden]");
		assertThat(read()).contains("banner.hidden = true").contains("banner.hidden = false");
	}

	/**
	 * An empty answer schedules the next question. This is the whole reason the
	 * banner can show work that started after the page was drawn: the predecessor
	 * followed one execution and stopped looking when it ended, so the next thing
	 * to run was invisible until somebody pressed F5.
	 */
	@Test
	void anEmptyAnswerIsAReasonToKeepAskingRatherThanToStop() throws Exception {
		assertThat(read()).contains("return IDLE_MILLIS;").contains("schedule(render(snapshot))");
	}

	/**
	 * How many are behind the primary, and which ones. "Who is ahead of my geo
	 * dataset update?" was a real question with no answer on any screen; the list
	 * arrives in the order the worker would take it, so naming it costs nothing.
	 */
	@Test
	void theQueueBehindThePrimaryIsNamed() throws Exception {
		// The count is not computed here any more: the sentence arrives written,
		// because telling running from queued is a reading of the domain.
		assertThat(read()).contains("snapshot.others").contains("others.title")
				.contains("snapshot.othersLabel").doesNotContain("totalActive");
	}

	/**
	 * Two bars, like any unpacker: the overall one counts what is done, the second
	 * one is the step still being worked on. With only the first, a geodata update
	 * that has imported all three administrative levels reads 100% while it is
	 * still writing the supplemental files - which is exactly what it did.
	 */
	@Test
	void theStepBeingWorkedOnGetsABarOfItsOwn() throws Exception {
		String layout = Files.readString(Path.of("src/main/resources/templates/fragments/layout.html"));
		String css = Files.readString(Path.of("src/main/resources/static/css/layout.css"));

		assertThat(layout).contains("id=\"executionActivityStepProgress\"")
				.contains("id=\"executionActivityStepPercent\"");
		assertThat(css).contains(".active-execution-progress.step");
		assertThat(read()).contains("activity.currentItemPercent").contains("js.activity.currentStep");
	}

	/**
	 * The step bar has to be legible on both palettes, and that takes a colour of
	 * its own on each - which is what {@code --progress-step} is for.
	 *
	 * <p>
	 * This used to require {@code --accent-strong}, on the grounds that it inverts
	 * between the themes. It does, but inverting is not the same as contrasting
	 * with the bar above: on the light palette it is #1d4ed8 beside the overall
	 * bar's #2563eb, two shades of one blue, measured at a contrast ratio of 1,30
	 * - the two bars read as a single thick one. Requiring the token was pinning
	 * the means; what has to hold is that the step bar has a value of its own,
	 * stated for both themes.
	 */
	@Test
	void theStepBarIsLegibleOnBothThemes() throws Exception {
		String base = Files.readString(Path.of("src/main/resources/static/css/base.css"));
		String css = Files.readString(Path.of("src/main/resources/static/css/layout.css"));

		assertThat(css).contains("background: var(--progress-step)");
		assertThat(base.split("--progress-step:", -1)).as("--progress-step must be defined in both themes")
				.hasSize(3);
	}

	/**
	 * Work that reports a single level of progress must not grow a second empty
	 * bar - an inventory has nothing to say about a step, and a bar stuck at zero
	 * reads as stuck.
	 */
	@Test
	void theStepBarExistsOnlyWhileThereIsAStepToReport() throws Exception {
		assertThat(read()).contains("stepProgress.hidden = !reported").contains("stepPercent.hidden = !reported");
	}

	/** A failed poll says nothing about the work, so it must not end the loop. */
	@Test
	void aFailedPollBacksOffInsteadOfGivingUp() throws Exception {
		assertThat(read()).contains(".catch(").contains("schedule(ERROR_MILLIS)");
	}

	/**
	 * A slow answer must not leave a queue of requests behind it, which is how a
	 * polling banner turns into load on the very machine it is reporting on.
	 */
	@Test
	void onlyOneRequestIsInFlightAtATime() throws Exception {
		assertThat(read()).contains("if (polling) {").contains("polling = true;").contains("polling = false;");
	}

	/**
	 * A tab nobody is looking at still costs a request every few seconds, and a
	 * machine left open overnight would pay for all of them.
	 */
	@Test
	void aHiddenTabAsksFarLessOften() throws Exception {
		assertThat(read()).contains("document.hidden ? HIDDEN_MILLIS").contains("visibilitychange");
	}

	/**
	 * Every flow that queues work either reloads the page or navigates, so a page
	 * that has just opened is exactly when the answer matters most - and it asks
	 * then rather than waiting for the first interval to come round. The script
	 * sits below the banner in the shell, which is what makes that safe.
	 */
	@Test
	void aPageThatHasJustLoadedAsksImmediately() throws Exception {
		String layout = Files.readString(Path.of("src/main/resources/templates/fragments/layout.html"));

		assertThat(layout.indexOf("id=\"executionActivity\"")).isLessThan(layout.indexOf("/js/execution-activity.js"));
		assertThat(read()).contains("poll();").doesNotContain("addEventListener(\"DOMContentLoaded\"");
	}

	/**
	 * Pages outside the app shell - login, the error page - have no banner, and
	 * reaching for one that is not there would throw on every one of them.
	 */
	@Test
	void aPageWithoutTheShellDoesNothingAtAll() throws Exception {
		assertThat(read()).contains("if (!banner) {");
	}

	private boolean asksForActivity(Path script) {
		try {
			return Files.readString(script).contains("/api/execution-activity");
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

	private String read() throws Exception {
		return Files.readString(BANNER);
	}
}