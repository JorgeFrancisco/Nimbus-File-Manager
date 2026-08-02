package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import br.com.jorgemelo.nimbusfilemanager.backup.application.RestoreNotice;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.ExecutionStatusNames;
import br.com.jorgemelo.nimbusfilemanager.statistics.application.StatisticsService;
import jakarta.servlet.http.HttpServletResponse;

@Controller
public class DashboardWebController {

	/**
	 * Rows per page of the "Execucoes" table on the Dashboard - same size the old
	 * standalone Execucoes screen showed at once, before it was folded into this
	 * page.
	 */
	private static final int EXECUTIONS_PAGE_SIZE = 20;

	private final ExecutionQueryService executionQueryService;
	private final StatisticsService statisticsService;
	private final AppSettingService appSettingService;
	private final RestoreNotice restoreNotice;

	public DashboardWebController(ExecutionQueryService executionQueryService, StatisticsService statisticsService,
			AppSettingService appSettingService, RestoreNotice restoreNotice) {
		this.executionQueryService = executionQueryService;
		this.statisticsService = statisticsService;
		this.appSettingService = appSettingService;
		this.restoreNotice = restoreNotice;
	}

	@GetMapping("/app")
	public String dashboard(Model model) {
		if (appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "").isBlank()) {
			return "redirect:/app/onboarding";
		}

		Page<ExecutionResponse> executionsPage = executionQueryService.page(0, EXECUTIONS_PAGE_SIZE);

		model.addAttribute("summary", statisticsService.summary());
		model.addAttribute("executionsPage", executionsPage);
		model.addAttribute("hasRunningExecutions", hasRunningExecutions(executionsPage));
		// Read once and gone: this is the first screen a restore started from the
		// welcome wizard lands on, and the notice has nothing to say on the second.
		model.addAttribute("catalogRestored", restoreNotice.consume().orElse(null));

		return "app/dashboard";
	}

	/**
	 * Returns just the next page of execution rows, rendered as an HTML fragment
	 * (no page shell), so dashboard.js can append it to the table for infinite
	 * scroll instead of the fixed top-20 snapshot the merged-in Execucoes screen
	 * used to show. Mirrors how FileExplorerWebController's "/app/files/items"
	 * backs Arquivos' own infinite scroll.
	 */
	@GetMapping("/app/executions/items")
	public String executionItems(@RequestParam(defaultValue = "0") Integer page, HttpServletResponse response,
			Model model) {
		Page<ExecutionResponse> executionsPage = executionQueryService.page(page, EXECUTIONS_PAGE_SIZE);

		model.addAttribute("executionsPage", executionsPage);
		response.setHeader("X-Has-Next", Boolean.toString(executionsPage.hasNext()));

		return "app/dashboard :: rows";
	}

	private boolean hasRunningExecutions(Page<ExecutionResponse> executionsPage) {
		return executionsPage.getContent().stream()
				.anyMatch(execution -> ExecutionStatusNames.IN_PROGRESS_NAMES.contains(execution.status()));
	}
}