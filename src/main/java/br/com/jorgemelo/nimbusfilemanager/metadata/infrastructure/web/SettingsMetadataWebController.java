package br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.web;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.MetadataRebuildAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataRebuildPreferences;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildRequest;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MetadataRebuildField;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.SecurityUtils;

/**
 * Starts the metadata rebuild from the settings page. The rebuild runs in the
 * background and the panel polls its progress, because a library-wide pass takes
 * far longer than a request should be held open - the REST endpoint of the same
 * service stays synchronous for scripted use.
 */
@Controller
public class SettingsMetadataWebController extends LocalizedComponent {

	private final MetadataRebuildAsyncRunner metadataRebuildAsyncRunner;
	private final UserPagePreferenceService userPagePreferenceService;
	private final InventoryRunningState inventoryRunningState;

	public SettingsMetadataWebController(MetadataRebuildAsyncRunner metadataRebuildAsyncRunner,
			UserPagePreferenceService userPagePreferenceService, InventoryRunningState inventoryRunningState) {
		this.metadataRebuildAsyncRunner = metadataRebuildAsyncRunner;
		this.userPagePreferenceService = userPagePreferenceService;
		this.inventoryRunningState = inventoryRunningState;
	}

	@PostMapping("/app/settings/metadata/rebuild")
	public String rebuildMetadata(@ModelAttribute MetadataRebuildRequest request, Authentication authentication,
			RedirectAttributes redirectAttributes) {
		// Remember the choices before any guard below can return: the form must
		// reopen on what was asked for even when the rebuild could not start.
		remember(request, authentication);

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

		metadataRebuildAsyncRunner.rebuild(request);

		redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS,
				message("backend.settings.metadataRebuildStarted"));

		return SharedConstants.REDIRECT_SETTINGS;
	}

	private void remember(MetadataRebuildRequest request, Authentication authentication) {
		String username = SecurityUtils.usernameOr(authentication, "system");

		save(username, MetadataRebuildPreferences.SOURCE_PATH_KEY,
				request.sourcePath() == null ? "" : request.sourcePath());
		save(username, MetadataRebuildPreferences.FIELDS_KEY, fields(request.refresh()));
		save(username, MetadataRebuildPreferences.DRY_RUN_KEY, String.valueOf(request.dryRun()));
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