package br.com.jorgemelo.nimbusfilemanager.security.application;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.security.application.constants.AccessMessages;
import br.com.jorgemelo.nimbusfilemanager.security.application.constants.SecurityConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Records every logout in the access log and tells them apart by cause: a
 * deliberate "Sair" click (or the browser auto-submitting the idle-timeout
 * form) both hit the same {@code /logout} endpoint, but the idle-timeout form
 * adds a {@code reason=inactivity} field so this handler can log a distinct
 * event type and send the user back to the login page with a matching message.
 */
@Component
public class AppLogoutSuccessHandler implements LogoutSuccessHandler {

	private static final String REASON_PARAMETER = "reason";
	private static final String INACTIVITY_REASON = "inactivity";

	private final UserAccessLogService userAccessLogService;

	public AppLogoutSuccessHandler(UserAccessLogService userAccessLogService) {
		this.userAccessLogService = userAccessLogService;
	}

	@Override
	public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
			throws IOException {
		boolean idle = INACTIVITY_REASON.equals(request.getParameter(REASON_PARAMETER));

		if (authentication != null) {
			String eventType = idle ? SecurityConstants.LOGOUT_INACTIVITY : SecurityConstants.LOGOUT;

			String message = idle ? AccessMessages.LOGOUT_BY_INACTIVITY : AccessMessages.LOGOUT_COMPLETED;

			RequestClientInfo client = RequestClientInfo.from(request);

			userAccessLogService.recordAccess(authentication.getName(), eventType, "SUCCESS", client.ipAddress(),
					client.userAgent(), message);
		}

		response.sendRedirect(idle ? "/login?idle" : "/login?logout");
	}
}