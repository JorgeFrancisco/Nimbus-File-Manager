package br.com.jorgemelo.nimbusfilemanager.security.infrastructure.web;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import br.com.jorgemelo.nimbusfilemanager.security.application.AccessLogLabels;
import br.com.jorgemelo.nimbusfilemanager.security.application.UserAccessLogService;
import br.com.jorgemelo.nimbusfilemanager.security.application.dto.AccessLogView;
import br.com.jorgemelo.nimbusfilemanager.security.domain.model.UserAccessLog;

@Controller
public class AccessLogWebController {

	private final UserAccessLogService userAccessLogService;
	private final AccessLogLabels accessLogLabels;

	public AccessLogWebController(UserAccessLogService userAccessLogService, AccessLogLabels accessLogLabels) {
		this.userAccessLogService = userAccessLogService;
		this.accessLogLabels = accessLogLabels;
	}

	@GetMapping("/app/accesses")
	public String accesses(@RequestParam(required = false) String email, Model model) {
		String normalizedEmail = email == null ? "" : email.trim();

		boolean searched = !normalizedEmail.isBlank();

		model.addAttribute("email", normalizedEmail);
		model.addAttribute("searched", searched);
		model.addAttribute("accessLogs", searched ? toViews(userAccessLogService.findByEmail(normalizedEmail)) : List.of());

		return "app/accesses";
	}

	private List<AccessLogView> toViews(List<UserAccessLog> logs) {
		return logs.stream().map(this::toView).toList();
	}

	private AccessLogView toView(UserAccessLog log) {
		return new AccessLogView(log.getUsername(), accessLogLabels.eventType(log.getEventType()),
				accessLogLabels.status(log.getStatus()), log.getStatus(),
				accessLogLabels.messageLabel(log.getMessage()), log.getIpAddress(), log.getCreatedAt());
	}
}