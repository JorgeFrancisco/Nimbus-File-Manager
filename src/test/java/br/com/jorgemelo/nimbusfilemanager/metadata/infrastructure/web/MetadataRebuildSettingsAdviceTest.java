package br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.MetadataRunReader;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataRebuildPreferences;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildRequest;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MetadataRebuildField;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;

/**
 * Read side of the metadata rebuild panel: progress attributes and the choices
 * the form reopens on.
 */
class MetadataRebuildSettingsAdviceTest {

	private final MetadataRunReader runner = mock(MetadataRunReader.class);
	private final UserPagePreferenceService userPagePreferenceService = mock(UserPagePreferenceService.class);
	private final MetadataRebuildSettingsAdvice advice = new MetadataRebuildSettingsAdvice(runner,
			userPagePreferenceService);

	@Test
	void publishesTheProgressOfTheRunningRebuild() {
		when(runner.isRunning()).thenReturn(true);
		when(runner.processed()).thenReturn(30L);
		when(runner.total()).thenReturn(100L);
		when(runner.percent()).thenReturn(30d);
		when(runner.etaSeconds()).thenReturn(90L);

		Model model = new ConcurrentModel();

		advice.addTo(model, null);

		Assertions.assertThat(model.getAttribute("metadataRebuildRunning")).isEqualTo(true);
		Assertions.assertThat(model.getAttribute("metadataRebuildProcessed")).isEqualTo(30L);
		Assertions.assertThat(model.getAttribute("metadataRebuildTotal")).isEqualTo(100L);
		Assertions.assertThat(model.getAttribute("metadataRebuildPercent")).isEqualTo(30.0);
		Assertions.assertThat(model.getAttribute("metadataRebuildEta")).isEqualTo(90L);
	}

	/**
	 * ALL means the same as ticking every box, so offering it would let the admin
	 * pick two spellings of one choice.
	 */
	@Test
	void offersEverySelectableFieldExceptTheAllShortcut() {
		Model model = new ConcurrentModel();

		advice.addTo(model, null);

		Assertions.assertThat(model.getAttribute("metadataRebuildFields"))
				.asInstanceOf(InstanceOfAssertFactories.list(MetadataRebuildField.class))
				.contains(MetadataRebuildField.DATE, MetadataRebuildField.SUBCATEGORY)
				.doesNotContain(MetadataRebuildField.ALL);
	}

	/**
	 * The panel says when the last run started, because that is what "continue"
	 * skips by - without it the choice would be blind.
	 */
	@Test
	void publishesTheScopeAndWhenTheLastRunStarted() {
		when(userPagePreferenceService.find("system", MetadataRebuildPreferences.PAGE_KEY))
				.thenReturn(Map.of(MetadataRebuildPreferences.SCOPE_KEY, "ALL", MetadataRebuildPreferences.LAST_RUN_KEY,
						"2026-07-26T11:16:13"));

		Model model = new ConcurrentModel();

		advice.addTo(model, null);

		Assertions.assertThat(model.getAttribute("metadataRebuildScope")).isEqualTo("ALL");
		Assertions.assertThat(model.getAttribute("metadataRebuildLastRunAt"))
				.isEqualTo(LocalDateTime.of(2026, Month.JULY, 26, 11, 16, 13));
	}

	/**
	 * An unreadable or absent mark leaves nothing to continue from, and the screen
	 * has to say so rather than break rendering the panel.
	 */
	@Test
	void reportsNoPreviousRunWhenTheMarkIsMissingOrUnreadable() {
		Model model = new ConcurrentModel();

		advice.addTo(model, null);

		Assertions.assertThat(model.getAttribute("metadataRebuildScope")).isEqualTo("CONTINUE");
		Assertions.assertThat(model.getAttribute("metadataRebuildLastRunAt")).isNull();

		when(userPagePreferenceService.find("system", MetadataRebuildPreferences.PAGE_KEY))
				.thenReturn(Map.of(MetadataRebuildPreferences.LAST_RUN_KEY, "ontem"));

		Model broken = new ConcurrentModel();

		advice.addTo(broken, null);

		Assertions.assertThat(broken.getAttribute("metadataRebuildLastRunAt")).isNull();
	}

	/**
	 * The screen states the per-run ceiling, so it comes from the single place that
	 * defines it instead of being retyped into the wording.
	 */
	@Test
	void publishesThePerRunCeilingTheScreenStates() {
		Model model = new ConcurrentModel();

		advice.addTo(model, null);

		Assertions.assertThat(model.getAttribute("metadataRebuildLimit")).isEqualTo(MetadataRebuildRequest.MAX_LIMIT);
	}

	@Test
	void reopensTheFormOnTheSavedChoices() {
		when(userPagePreferenceService.find("system", MetadataRebuildPreferences.PAGE_KEY)).thenReturn(
				Map.of(MetadataRebuildPreferences.SOURCE_PATH_KEY, "D:\\photos", MetadataRebuildPreferences.FIELDS_KEY,
						"GPS,CAMERA", MetadataRebuildPreferences.DRY_RUN_KEY, "true"));

		Model model = new ConcurrentModel();

		advice.addTo(model, null);

		Assertions.assertThat(model.getAttribute("metadataRebuildSourcePath")).isEqualTo("D:\\photos");
		Assertions.assertThat(model.getAttribute("metadataRebuildSelectedFields")).isEqualTo(List.of("GPS", "CAMERA"));
		Assertions.assertThat(model.getAttribute("metadataRebuildDryRun")).isEqualTo(true);
	}

	/**
	 * With nothing saved yet the form opens on the two fields that answer the
	 * reason the panel exists, instead of on an empty selection that would rebuild
	 * nothing.
	 */
	@Test
	void defaultsToReclassifyingAndRereadingTheDate() {
		Model model = new ConcurrentModel();

		advice.addTo(model, null);

		Assertions.assertThat(model.getAttribute("metadataRebuildSelectedFields"))
				.isEqualTo(List.of("SUBCATEGORY", "DATE"));
		Assertions.assertThat(model.getAttribute("metadataRebuildSourcePath")).isEqualTo("");
		Assertions.assertThat(model.getAttribute("metadataRebuildDryRun")).isEqualTo(false);
	}
}