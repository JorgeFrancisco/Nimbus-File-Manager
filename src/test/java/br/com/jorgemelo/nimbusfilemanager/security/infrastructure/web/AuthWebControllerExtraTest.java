package br.com.jorgemelo.nimbusfilemanager.security.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import br.com.jorgemelo.nimbusfilemanager.security.application.AppUserAccountService;
import br.com.jorgemelo.nimbusfilemanager.security.application.AppUserDetailsService;
import br.com.jorgemelo.nimbusfilemanager.security.application.TwoFactorLoginService;
import br.com.jorgemelo.nimbusfilemanager.security.application.constants.SecurityConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Security;

/**
 * GET screens and the standalone change-password flow of the auth controller,
 * plus the two-factor screen's "no pending login" guard - the paths not
 * exercised by the two-factor tests in {@code WebControllersTest}.
 */
class AuthWebControllerExtraTest {

	private final AppUserAccountService appUserAccountService = mock(AppUserAccountService.class);
	private final AppUserDetailsService appUserDetailsService = mock(AppUserDetailsService.class);
	private final TwoFactorLoginService twoFactorLoginService = mock(TwoFactorLoginService.class);

	private AuthWebController controller(boolean googleEnabled, String clientId, String clientSecret) {
		GoogleLoginStatus googleLoginStatus = new GoogleLoginStatus(authProps(googleEnabled), clientId, clientSecret);

		return new AuthWebController(appUserAccountService, appUserDetailsService, twoFactorLoginService,
				googleLoginStatus);
	}

	private NimbusFileManagerProperties authProps(boolean googleEnabled) {
		return new NimbusFileManagerProperties(null, null, null, null,
				new Security(0, 0, 0, googleEnabled, "admin", "admin"), null);
	}

	@Test
	void loginExposesGoogleAvailabilityWhenConfigured() {
		ExtendedModelMap model = new ExtendedModelMap();

		String view = controller(true, "client-id", "client-secret").login(model);

		Assertions.assertThat(view).isEqualTo("auth/login");
		Assertions.assertThat(model).containsEntry("googleLoginEnabled", true).containsEntry("googleLoginAvailable",
				true);
	}

	@Test
	void loginMarksGoogleUnavailableWhenNotConfigured() {
		ExtendedModelMap model = new ExtendedModelMap();

		controller(true, "", "").login(model);

		Assertions.assertThat(model).containsEntry("googleLoginAvailable", false);
	}

	@Test
	void changePasswordGetRendersView() {
		ExtendedModelMap model = new ExtendedModelMap();

		String view = controller(false, "", "").changePassword(model);

		Assertions.assertThat(view).isEqualTo("auth/change-password");
		Assertions.assertThat(model).containsEntry("googleLoginEnabled", false);
	}

	@Test
	void changePasswordPostRejectsMismatchedConfirmation() {
		RedirectAttributesModelMap attributes = new RedirectAttributesModelMap();

		String view = controller(false, "", "").changePassword("user@x", "old", "new1", "new2", attributes);

		Assertions.assertThat(view).isEqualTo("redirect:/change-password");
		Assertions.assertThat(attributes.getFlashAttributes()).containsKey("passwordError");
	}

	@Test
	void changePasswordPostRedirectsToLoginOnSuccess() {
		RedirectAttributesModelMap attributes = new RedirectAttributesModelMap();

		String view = controller(false, "", "").changePassword("user@x", "old", "newSecret", "newSecret", attributes);

		Assertions.assertThat(view).isEqualTo("redirect:/login");
		Assertions.assertThat(attributes.getFlashAttributes()).extractingByKey("passwordChanged").isEqualTo(true);
	}

	@Test
	void changePasswordPostReportsServiceError() {
		doThrow(new IllegalArgumentException("Senha atual inválida.")).when(appUserAccountService)
				.changePassword(eq("user@x"), any(), any());

		RedirectAttributesModelMap attributes = new RedirectAttributesModelMap();

		String view = controller(false, "", "").changePassword("user@x", "wrong", "newSecret", "newSecret", attributes);

		Assertions.assertThat(view).isEqualTo("redirect:/change-password");
		Assertions.assertThat(attributes.getFlashAttributes()).extractingByKey("passwordError")
				.isEqualTo("Senha atual inválida.");
	}

	@Test
	void twoFactorScreenRedirectsToLoginWhenNoPendingLogin() {
		MockHttpServletRequest request = new MockHttpServletRequest();

		String view = controller(false, "", "").twoFactor(request, new ExtendedModelMap());

		Assertions.assertThat(view).isEqualTo("redirect:/login");
	}

	@Test
	void twoFactorScreenRendersWhenLoginIsPending() {
		MockHttpServletRequest request = new MockHttpServletRequest();

		request.getSession().setAttribute(SecurityConstants.PENDING_USERNAME, "user@x");

		ExtendedModelMap model = new ExtendedModelMap();

		String view = controller(false, "", "").twoFactor(request, model);

		Assertions.assertThat(view).isEqualTo("auth/two-factor");
		Assertions.assertThat(model).containsEntry("username", "user@x");
	}

	@Test
	void verifyTwoFactorRedirectsToLoginWhenNoPendingLogin() {
		MockHttpServletRequest request = new MockHttpServletRequest();

		String view = controller(false, "", "").verifyTwoFactor("123456", request, new ExtendedModelMap());

		Assertions.assertThat(view).isEqualTo("redirect:/login");
	}
}