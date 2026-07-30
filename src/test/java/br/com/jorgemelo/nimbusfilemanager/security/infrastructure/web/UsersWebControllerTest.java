package br.com.jorgemelo.nimbusfilemanager.security.infrastructure.web;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.security.application.AppUserAccountService;
import br.com.jorgemelo.nimbusfilemanager.security.domain.enums.Role;
import br.com.jorgemelo.nimbusfilemanager.security.domain.model.AppUser;

class UsersWebControllerTest {

	@Test
	void usersShouldListAndCreateUsers() {
		AppUserAccountService appUserAccountService = mock(AppUserAccountService.class);
		UsersWebController controller = new UsersWebController(appUserAccountService,
				mock(UserPagePreferenceService.class));
		ExtendedModelMap model = new ExtendedModelMap();
		AppUser user = user();

		when(appUserAccountService.searchUsers("admin", 0, 50))
				.thenReturn(new PageImpl<>(List.of(user), PageRequest.of(0, 50), 1));

		Assertions.assertThat(controller.users("admin", 0, 50, null, model)).isEqualTo("app/users");
		Assertions.assertThat(model).containsEntry("users", List.of(user)).containsEntry("q", "admin")
				.containsEntry("size", 50);
		Assertions.assertThat(
				controller.create("new@example.com", "New User", "secret1", "USER", new RedirectAttributesModelMap()))
				.isEqualTo("redirect:/app/users");
		verify(appUserAccountService).createUser("new@example.com", "New User", "secret1", "USER");
	}

	@Test
	void usersShouldFallBackToTheSavedPageSize() {
		AppUserAccountService appUserAccountService = mock(AppUserAccountService.class);
		var preferences = mock(UserPagePreferenceService.class);
		when(preferences.find(ArgumentMatchers.any(), ArgumentMatchers.any()))
				.thenReturn(Map.of(UsersWebController.SIZE_KEY, "100"));
		when(appUserAccountService.searchUsers(null, 0, 100))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));
		ExtendedModelMap model = new ExtendedModelMap();

		new UsersWebController(appUserAccountService, preferences).users(null, 0, null, null, model);

		Assertions.assertThat(model).containsEntry("size", 100);
		verify(appUserAccountService).searchUsers(null, 0, 100);
	}

	private AppUser user() {
		return AppUser.builder().username("admin").passwordHash("hash").displayName("Admin").role(Role.ADMIN)
				.enabled(true).twoFactorEnabled(false).build();
	}

	/**
	 * A refused creation has to say why on the screen; the operator would
	 * otherwise be sent back to a list that simply lacks the new user.
	 */
	@Test
	void usersShouldReportWhyACreationWasRefused() {
		AppUserAccountService appUserAccountService = mock(AppUserAccountService.class);
		UsersWebController controller = new UsersWebController(appUserAccountService,
				mock(UserPagePreferenceService.class));
		RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

		doThrow(new IllegalArgumentException("Email already registered.")).when(appUserAccountService)
				.createUser("taken@example.com", "Taken", "secret1", "USER");

		Assertions.assertThat(controller.create("taken@example.com", "Taken", "secret1", "USER", redirect))
				.isEqualTo("redirect:/app/users");
		Assertions.assertThat(redirect.getFlashAttributes().get("userError")).hasToString("Email already registered.");
		Assertions.assertThat(redirect.getFlashAttributes()).doesNotContainKey("userCreated");
	}
}