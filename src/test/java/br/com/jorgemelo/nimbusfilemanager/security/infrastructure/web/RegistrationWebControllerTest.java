package br.com.jorgemelo.nimbusfilemanager.security.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import br.com.jorgemelo.nimbusfilemanager.notification.application.EmailService;
import br.com.jorgemelo.nimbusfilemanager.security.application.AppUserAccountService;
import br.com.jorgemelo.nimbusfilemanager.security.domain.model.AppUser;

class RegistrationWebControllerTest {

	@Test
	void registerShouldShowFormOnGet() {
		AppUserAccountService appUserAccountService = mock(AppUserAccountService.class);
		EmailService emailService = mock(EmailService.class);
		RegistrationWebController controller = new RegistrationWebController(appUserAccountService, emailService);

		Assertions.assertThat(controller.register()).isEqualTo("auth/register");
	}

	@Test
	void registerShouldCreateUserLogConfirmationLinkAndRedirectToLogin() {
		AppUserAccountService appUserAccountService = mock(AppUserAccountService.class);
		EmailService emailService = mock(EmailService.class);
		RegistrationWebController controller = new RegistrationWebController(appUserAccountService, emailService);
		ExtendedModelMap model = new ExtendedModelMap();
		MockHttpServletRequest request = new MockHttpServletRequest();
		AppUser user = AppUser.builder().username("new@example.com").confirmationToken("tok-123").build();
		var redirectAttributes = new RedirectAttributesModelMap();

		request.setScheme("http");
		request.setServerName("localhost");
		request.setServerPort(8088);
		when(appUserAccountService.register("new@example.com", "New User", "secret1")).thenReturn(user);

		String view = controller.register("new@example.com", "New User", "secret1", "secret1", request, model,
				redirectAttributes);

		Assertions.assertThat(view).isEqualTo("redirect:/login");
		Assertions.assertThat(redirectAttributes.getFlashAttributes()).extractingByKey("registered").isEqualTo(true);
		verify(emailService).sendConfirmationEmail("new@example.com", "http://localhost:8088/confirm?token=tok-123");
	}

	@Test
	void registerShouldOmitDefaultPortFromConfirmationLink() {
		AppUserAccountService appUserAccountService = mock(AppUserAccountService.class);
		EmailService emailService = mock(EmailService.class);
		RegistrationWebController controller = new RegistrationWebController(appUserAccountService, emailService);
		ExtendedModelMap model = new ExtendedModelMap();
		MockHttpServletRequest request = new MockHttpServletRequest();
		AppUser user = AppUser.builder().username("new@example.com").confirmationToken("tok-123").build();

		request.setScheme("https");
		request.setServerName("nimbus-file-manager.example.com");
		request.setServerPort(443);
		when(appUserAccountService.register("new@example.com", "New User", "secret1")).thenReturn(user);

		String view = controller.register("new@example.com", "New User", "secret1", "secret1", request, model,
				new RedirectAttributesModelMap());

		Assertions.assertThat(view).isEqualTo("redirect:/login");
	}

	@Test
	void registerShouldRejectMismatchedPasswordsWithoutCallingService() {
		AppUserAccountService appUserAccountService = mock(AppUserAccountService.class);
		EmailService emailService = mock(EmailService.class);
		RegistrationWebController controller = new RegistrationWebController(appUserAccountService, emailService);
		ExtendedModelMap model = new ExtendedModelMap();
		MockHttpServletRequest request = new MockHttpServletRequest();

		String view = controller.register("new@example.com", "New User", "secret1", "different", request, model,
				new RedirectAttributesModelMap());

		Assertions.assertThat(view).isEqualTo("auth/register");
		Assertions.assertThat(model).containsEntry("registerError", "As senhas não conferem.");
		verify(appUserAccountService, never()).register(any(), any(), any());
	}

	@Test
	void registerShouldShowServiceErrorOnInvalidData() {
		AppUserAccountService appUserAccountService = mock(AppUserAccountService.class);
		EmailService emailService = mock(EmailService.class);
		RegistrationWebController controller = new RegistrationWebController(appUserAccountService, emailService);
		ExtendedModelMap model = new ExtendedModelMap();
		MockHttpServletRequest request = new MockHttpServletRequest();

		when(appUserAccountService.register("dup@example.com", "Dup", "secret1"))
				.thenThrow(new IllegalArgumentException("E-mail já cadastrado."));

		String view = controller.register("dup@example.com", "Dup", "secret1", "secret1", request, model,
				new RedirectAttributesModelMap());

		Assertions.assertThat(view).isEqualTo("auth/register");
		Assertions.assertThat(model).containsEntry("registerError", "E-mail já cadastrado.");
	}

	@Test
	void confirmShouldFlashSuccessWhenTokenIsValid() {
		AppUserAccountService appUserAccountService = mock(AppUserAccountService.class);
		EmailService emailService = mock(EmailService.class);
		RegistrationWebController controller = new RegistrationWebController(appUserAccountService, emailService);
		var redirectAttributes = new RedirectAttributesModelMap();

		String view = controller.confirm("tok-123", redirectAttributes);

		Assertions.assertThat(view).isEqualTo("redirect:/login");
		Assertions.assertThat(redirectAttributes.getFlashAttributes()).extractingByKey("confirmed").isEqualTo(true);
		verify(appUserAccountService).confirmRegistration("tok-123");
	}

	@Test
	void confirmShouldFlashErrorWhenTokenIsInvalid() {
		AppUserAccountService appUserAccountService = mock(AppUserAccountService.class);
		EmailService emailService = mock(EmailService.class);
		RegistrationWebController controller = new RegistrationWebController(appUserAccountService, emailService);
		var redirectAttributes = new RedirectAttributesModelMap();

		when(appUserAccountService.confirmRegistration("bad"))
				.thenThrow(new IllegalArgumentException("Token de confirmação inválido."));

		String view = controller.confirm("bad", redirectAttributes);

		Assertions.assertThat(view).isEqualTo("redirect:/login");
		Assertions.assertThat(redirectAttributes.getFlashAttributes()).extractingByKey("confirmError")
				.isEqualTo("Token de confirmação inválido.");
	}
}