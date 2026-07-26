package br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateExclusionService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.SimilarityCaches;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * Restores files or folders to duplicate comparison, undoing an "Excluir da
 * comparação" done from the Duplicados screen. Both duplicate tabs pick the
 * restore up again: the exact queries re-filter on the next visit and both
 * similar caches (photo and video) are cleared so they recompute with the item
 * back in - a restore does not move any fingerprint, so the caches would
 * otherwise keep serving the stale, excluded-item-missing grouping.
 */
@Controller
public class SettingsDuplicateExclusionWebController extends LocalizedComponent {

	private final DuplicateExclusionService duplicateExclusionService;
	private final SimilarityCaches similarityCaches;

	@Autowired
	public SettingsDuplicateExclusionWebController(DuplicateExclusionService duplicateExclusionService,
			SimilarityCaches similarityCaches) {
		this.duplicateExclusionService = duplicateExclusionService;
		this.similarityCaches = similarityCaches;
	}

	/** Restores a single file to duplicate comparison. */
	@PostMapping("/app/settings/duplicate-exclusions/file/remove")
	public String removeDuplicateFileExclusion(@RequestParam Long id, RedirectAttributes redirectAttributes) {
		duplicateExclusionService.removeFileExclusion(id);
		similarityCaches.invalidateAll();

		redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS, message("backend.settings.dupExclRemoved"));

		return SharedConstants.REDIRECT_SETTINGS;
	}

	/** Restores a whole folder (recursively) to duplicate comparison. */
	@PostMapping("/app/settings/duplicate-exclusions/folder/remove")
	public String removeDuplicateFolderExclusion(@RequestParam Long id, RedirectAttributes redirectAttributes) {
		duplicateExclusionService.removeFolderExclusion(id);
		similarityCaches.invalidateAll();

		redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS, message("backend.settings.dupExclRemoved"));

		return SharedConstants.REDIRECT_SETTINGS;
	}
}