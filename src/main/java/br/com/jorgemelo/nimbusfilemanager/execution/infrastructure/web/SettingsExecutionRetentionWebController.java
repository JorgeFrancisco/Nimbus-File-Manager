package br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionRetentionService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * Cleanup of the execution history (and its telemetry) from the Sistema tab
 * (admin). {@code mode=age} deletes executions finished more than {@code value}
 * days ago; {@code mode=keep} keeps only the {@code value} most recent. It only
 * removes finished executions, so it is safe even during an inventory in
 * progress.
 */
@Controller
public class SettingsExecutionRetentionWebController extends LocalizedComponent {

	private final ExecutionRetentionService executionRetentionService;

	@Autowired
	public SettingsExecutionRetentionWebController(ExecutionRetentionService executionRetentionService) {
		this.executionRetentionService = executionRetentionService;
	}

	@PostMapping("/app/settings/executions/cleanup")
	public String cleanupExecutions(@RequestParam(defaultValue = "age") String mode,
			@RequestParam(defaultValue = "0") int value, RedirectAttributes redirectAttributes) {
		try {
			int removed = "keep".equals(mode) ? executionRetentionService.keepLatest(value)
					: executionRetentionService.deleteOlderThanDays(value);

			String key = removed == 1 ? "backend.settings.executionRemoved" : "backend.settings.executionsRemoved";

			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_SUCCESS, message(key, removed));
		} catch (IllegalArgumentException e) {
			redirectAttributes.addFlashAttribute(SharedConstants.ATTR_ERROR, e.getMessage());
		}

		return SharedConstants.REDIRECT_SETTINGS;
	}
}