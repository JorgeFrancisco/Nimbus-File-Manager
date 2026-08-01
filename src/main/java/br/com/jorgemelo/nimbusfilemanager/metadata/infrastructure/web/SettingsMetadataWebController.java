package br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.web;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.MetadataRebuildAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataRebuildPreferences;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildRequest;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MetadataRebuildField;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MetadataRebuildScope;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.SecurityUtils;

/**
 * Starts the metadata rebuild from the settings page. The rebuild runs in the
 * background and the panel polls its progress, because a library-wide pass
 * takes far longer than a request should be held open - the REST endpoint of
 * the same service stays synchronous for scripted use.
 */
@Controller
public class SettingsMetadataWebController extends LocalizedComponent {

	private final MetadataRebuildAsyncRunner metadataRebuildAsyncRunner;
	private final UserPagePreferenceService userPagePreferenceService;
	private final InventoryRunningState inventoryRunningState;
	private final Clock clock;

	public SettingsMetadataWebController(MetadataRebuildAsyncRunner metadataRebuildAsyncRunner,
			UserPagePreferenceService userPagePreferenceService, InventoryRunningState inventoryRunningState,
			Clock clock) {
		this.metadataRebuildAsyncRunner = metadataRebuildAsyncRunner;
		this.userPagePreferenceService = userPagePreferenceService;
		this.inventoryRunningState = inventoryRunningState;
		this.clock = clock;
	}

	/**
	 * Explicit parameters instead of binding the request record straight from the
	 * form: an unticked checkbox sends no parameter at all, and the record declares
	 * {@code dryRun} as a primitive, so constructor binding rejected the post with
	 * a 400 whenever "simulate" was left off.
	 */
	@PostMapping("/app/settings/metadata/rebuild")
	public String rebuildMetadata(@RequestParam(required = false) String sourcePath,
			@RequestParam(required = false) List<MetadataRebuildField> refresh,
			@RequestParam(defaultValue = "false") boolean dryRun,
			@RequestParam(defaultValue = "CONTINUE") MetadataRebuildScope scope, Authentication authentication,
			RedirectAttributes redirectAttributes) {
		String username = SecurityUtils.usernameOr(authentication, "system");

		LocalDateTime startedAt = LocalDateTime.now(clock);

		MetadataRebuildRequest request = MetadataRebuildRequest.forFolder(sourcePath, refresh, dryRun,
				cutoff(scope, username));

		// Remember the choices before any guard below can return: the form must
		// reopen on what was asked for even when the rebuild could not start.
		remember(request, scope, username);

		if (request.sourcePath() == null || request.sourcePath().isBlank()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR,
					message("backend.settings.metadataRebuildFolderRequired"));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		if (inventoryRunningState.isRunning()) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR, message("backend.settings.blocked"));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		if (!metadataRebuildAsyncRunner.start(request)) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR,
					message("backend.settings.metadataRebuildRunning"));

			return SharedConstants.REDIRECT_SETTINGS;
		}

		// Stamped only once the run is really under way, and with the instant taken
		// before it: a file it rebuilds gets a later stamp, so the next continuing run
		// skips it and picks up exactly where this one stopped. A dry run changes
		// nothing on disk, so it must not move the mark either.
		if (!dryRun) {
			save(username, MetadataRebuildPreferences.LAST_RUN_KEY, startedAt.toString());
		}

		metadataRebuildAsyncRunner.rebuild(request);

		redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS,
				message("backend.settings.metadataRebuildStarted"));

		return SharedConstants.REDIRECT_SETTINGS;
	}

	/**
	 * Stores the panel choices without starting anything. The choices belong to the
	 * user, not to a rebuild, so picking a folder and leaving the screen must not
	 * discard it - the same contract the conversion screen has.
	 */
	@PostMapping("/app/settings/metadata/preferences")
	@ResponseBody
	public void rememberOptions(@RequestParam(required = false) String sourcePath,
			@RequestParam(required = false) List<MetadataRebuildField> refresh,
			@RequestParam(defaultValue = "false") boolean dryRun,
			@RequestParam(defaultValue = "CONTINUE") MetadataRebuildScope scope, Authentication authentication) {
		remember(MetadataRebuildRequest.forFolder(sourcePath, refresh, dryRun, null), scope,
				SecurityUtils.usernameOr(authentication, "system"));
	}

	/**
	 * The instant a continuing run skips by, or {@code null} to rebuild everything.
	 * With nothing recorded yet there is nothing to continue from, so the first run
	 * covers the folder either way.
	 */
	private LocalDateTime cutoff(MetadataRebuildScope scope, String username) {
		if (scope == MetadataRebuildScope.ALL) {
			return null;
		}

		String lastRun = userPagePreferenceService.find(username, MetadataRebuildPreferences.PAGE_KEY)
				.get(MetadataRebuildPreferences.LAST_RUN_KEY);

		return lastRun == null || lastRun.isBlank() ? null : LocalDateTime.parse(lastRun);
	}

	private void remember(MetadataRebuildRequest request, MetadataRebuildScope scope, String username) {
		save(username, MetadataRebuildPreferences.SOURCE_PATH_KEY,
				request.sourcePath() == null ? "" : request.sourcePath());
		save(username, MetadataRebuildPreferences.FIELDS_KEY, fields(request.refresh()));
		save(username, MetadataRebuildPreferences.DRY_RUN_KEY, String.valueOf(request.dryRun()));
		save(username, MetadataRebuildPreferences.SCOPE_KEY, scope.name());
	}

	private void save(String username, String key, String value) {
		userPagePreferenceService.save(username, MetadataRebuildPreferences.PAGE_KEY, key, value);
	}

	private String fields(List<MetadataRebuildField> refresh) {
		if (refresh == null) {
			return "";
		}

		return refresh.stream().map(MetadataRebuildField::name).collect(Collectors.joining(","));
	}
}