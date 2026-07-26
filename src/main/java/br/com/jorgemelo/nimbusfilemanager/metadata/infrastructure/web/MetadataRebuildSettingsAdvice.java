package br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.web;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.MetadataRebuildAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataRebuildPreferences;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MetadataRebuildField;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MetadataRebuildScope;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.settings.infrastructure.web.SettingsWebController;
import br.com.jorgemelo.nimbusfilemanager.shared.util.EnumUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.SecurityUtils;

/**
 * Read side of the metadata rebuild panel on the settings page: progress of the
 * running rebuild and the choices to reopen the form on.
 *
 * <p>
 * An advice instead of a dependency of the settings controller for two reasons:
 * that constructor is already at the seven-parameter limit, and this way the
 * settings domain does not have to know the metadata one - the panel's read side
 * stays in the domain that owns it, which is the direction the geolocation panel
 * would take if it were written today. Bound to the rendering controller rather
 * than to its package, because the folder browser sits in the same package and
 * is a {@code @RestController}: it would pay for the preference lookup on every
 * folder it lists and never use the result.
 */
@ControllerAdvice(assignableTypes = SettingsWebController.class)
public class MetadataRebuildSettingsAdvice {

	/**
	 * ALL is left out of the form: it means the same as ticking every box, so
	 * offering both would let the admin pick two spellings of one choice.
	 */
	private static final List<MetadataRebuildField> SELECTABLE_FIELDS = Arrays
			.stream(MetadataRebuildField.values()).filter(field -> field != MetadataRebuildField.ALL).toList();

	private final MetadataRebuildAsyncRunner metadataRebuildAsyncRunner;
	private final UserPagePreferenceService userPagePreferenceService;

	public MetadataRebuildSettingsAdvice(MetadataRebuildAsyncRunner metadataRebuildAsyncRunner,
			UserPagePreferenceService userPagePreferenceService) {
		this.metadataRebuildAsyncRunner = metadataRebuildAsyncRunner;
		this.userPagePreferenceService = userPagePreferenceService;
	}

	@ModelAttribute
	public void addTo(Model model, Authentication authentication) {
		model.addAttribute("metadataRebuildRunning", metadataRebuildAsyncRunner.isRunning());
		model.addAttribute("metadataRebuildProcessed", metadataRebuildAsyncRunner.processed());
		model.addAttribute("metadataRebuildTotal", metadataRebuildAsyncRunner.total());
		model.addAttribute("metadataRebuildPercent", metadataRebuildAsyncRunner.percent());
		model.addAttribute("metadataRebuildEta", metadataRebuildAsyncRunner.etaSeconds());
		model.addAttribute("metadataRebuildError", metadataRebuildAsyncRunner.lastError());
		model.addAttribute("metadataRebuildResult", metadataRebuildAsyncRunner.lastResult());
		model.addAttribute("metadataRebuildFields", SELECTABLE_FIELDS);

		Map<String, String> preferences = userPagePreferenceService
				.find(SecurityUtils.usernameOr(authentication, "system"), MetadataRebuildPreferences.PAGE_KEY);

		model.addAttribute("metadataRebuildSourcePath",
				preferences.getOrDefault(MetadataRebuildPreferences.SOURCE_PATH_KEY, ""));
		model.addAttribute("metadataRebuildSelectedFields",
				selectedFields(preferences.get(MetadataRebuildPreferences.FIELDS_KEY)));
		model.addAttribute("metadataRebuildDryRun",
				Boolean.parseBoolean(preferences.get(MetadataRebuildPreferences.DRY_RUN_KEY)));
		model.addAttribute("metadataRebuildScopes", MetadataRebuildScope.values());
		model.addAttribute("metadataRebuildScope",
				EnumUtils.valueOfOrDefault(MetadataRebuildScope.class,
						preferences.get(MetadataRebuildPreferences.SCOPE_KEY), MetadataRebuildScope.CONTINUE).name());
		model.addAttribute("metadataRebuildLastRunAt",
				lastRunAt(preferences.get(MetadataRebuildPreferences.LAST_RUN_KEY)));
	}

	/**
	 * When the last run started, so the screen can say what a continuing run would
	 * skip. Unparseable or absent means there is nothing to continue from yet.
	 */
	private LocalDateTime lastRunAt(String saved) {
		if (saved == null || saved.isBlank()) {
			return null;
		}

		try {
			return LocalDateTime.parse(saved);
		} catch (DateTimeParseException _) {
			return null;
		}
	}

	/**
	 * Reopens on what was picked last; with nothing saved yet, on the two fields
	 * that answer the reason this panel exists - reclassifying by family and
	 * re-reading the capture date.
	 */
	private List<String> selectedFields(String saved) {
		if (saved == null || saved.isBlank()) {
			return List.of(MetadataRebuildField.SUBCATEGORY.name(), MetadataRebuildField.DATE.name());
		}

		return List.of(saved.split(","));
	}
}