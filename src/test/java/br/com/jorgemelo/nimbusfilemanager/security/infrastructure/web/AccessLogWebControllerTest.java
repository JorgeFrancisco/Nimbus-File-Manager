package br.com.jorgemelo.nimbusfilemanager.security.infrastructure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import br.com.jorgemelo.nimbusfilemanager.security.application.AccessLogLabels;
import br.com.jorgemelo.nimbusfilemanager.security.application.UserAccessLogService;
import br.com.jorgemelo.nimbusfilemanager.security.application.constants.AccessMessages;
import br.com.jorgemelo.nimbusfilemanager.security.application.constants.SecurityConstants;
import br.com.jorgemelo.nimbusfilemanager.security.application.dto.AccessLogView;
import br.com.jorgemelo.nimbusfilemanager.security.domain.model.UserAccessLog;

class AccessLogWebControllerTest {

	private final UserAccessLogService userAccessLogService = mock(UserAccessLogService.class);
	private final AccessLogLabels accessLogLabels = mock(AccessLogLabels.class);
	private final AccessLogWebController controller = new AccessLogWebController(userAccessLogService, accessLogLabels);

	@Test
	void accessesShouldSearchHistoryByEmailOnlyWhenRequested() {
		ExtendedModelMap initialModel = new ExtendedModelMap();

		Assertions.assertThat(controller.accesses(null, initialModel)).isEqualTo("app/accesses");
		Assertions.assertThat(initialModel).containsEntry("searched", false).containsEntry("accessLogs", List.of());
	}

	@Test
	void accessesShouldExposeResolvedViewsForEachRowWhenSearched() {
		UserAccessLog log = UserAccessLog.builder().username("admin@nimbus-file-manager.local")
				.eventType(SecurityConstants.LOGIN_SUCCESS).status("SUCCESS").ipAddress("127.0.0.1")
				.message(AccessMessages.LOGIN_COMPLETED).createdAt(LocalDateTime.of(2026, Month.JULY, 24, 12, 0)).build();

		when(userAccessLogService.findByEmail("admin@nimbus-file-manager.local")).thenReturn(List.of(log));
		when(accessLogLabels.eventType(SecurityConstants.LOGIN_SUCCESS)).thenReturn("Login");
		when(accessLogLabels.status("SUCCESS")).thenReturn("Sucesso");
		when(accessLogLabels.messageLabel(AccessMessages.LOGIN_COMPLETED)).thenReturn("Login concluído.");

		ExtendedModelMap searchModel = new ExtendedModelMap();

		Assertions.assertThat(controller.accesses(" admin@nimbus-file-manager.local ", searchModel))
				.isEqualTo("app/accesses");
		Assertions.assertThat(searchModel).containsEntry("email", "admin@nimbus-file-manager.local")
				.containsEntry("searched", true).containsEntry("accessLogs",
						List.of(new AccessLogView("admin@nimbus-file-manager.local", "Login", "Sucesso", "SUCCESS",
								"Login concluído.", "127.0.0.1", LocalDateTime.of(2026, Month.JULY, 24, 12, 0))));
		verify(userAccessLogService).findByEmail("admin@nimbus-file-manager.local");
	}
}