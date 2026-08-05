package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web;

import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.InventoryWatchService;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.security.domain.enums.Role;
import br.com.jorgemelo.nimbusfilemanager.security.domain.model.AppUser;
import br.com.jorgemelo.nimbusfilemanager.security.domain.repository.AppUserRepository;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.update.application.UpdateCheckService;

@ExtendWith(MockitoExtension.class)
class AppViewModelAdviceTest {

	@Mock
	private UserPagePreferenceService userPagePreferenceService;

	@Mock
	private AppSettingService appSettingService;

	@Mock
	private InventoryWatchService inventoryWatchService;

	@Mock
	private AppUserRepository appUserRepository;

	@Test
	void appVersionShouldReturnConfiguredValue() {
		AppViewModelAdvice advice = advice("1.2.3");

		Assertions.assertThat(advice.appVersion()).isEqualTo("1.2.3");
	}

	@Test
	void sidebarCollapsedShouldReturnFalseWhenNotAuthenticated() {
		AppViewModelAdvice advice = advice("1.0.0");

		Assertions.assertThat(advice.sidebarCollapsed(null)).isFalse();

		Authentication anonymous = new UsernamePasswordAuthenticationToken("anonymousUser", null);

		Assertions.assertThat(advice.sidebarCollapsed(anonymous)).isFalse();
	}

	@Test
	void idleTimeoutMinutesShouldDelegateToAppSettingServiceWithFiveMinuteDefault() {
		AppViewModelAdvice advice = advice("1.0.0");

		when(appSettingService.intValue(SettingsConstants.IDLE_TIMEOUT_MINUTES, 5)).thenReturn(15);

		Assertions.assertThat(advice.idleTimeoutMinutes()).isEqualTo(15);
	}

	@Test
	void currentUserShouldReturnNullWhenNotAuthenticated() {
		AppViewModelAdvice advice = advice("1.0.0");

		Authentication anonymous = new UsernamePasswordAuthenticationToken("anonymousUser", null);

		Assertions.assertThat(advice.currentUser(null)).isNull();
		Assertions.assertThat(advice.currentUser(anonymous)).isNull();
	}

	@Test
	void currentUserShouldLookUpAuthenticatedUserByUsername() {
		AppViewModelAdvice advice = advice("1.0.0");

		Authentication authenticated = new UsernamePasswordAuthenticationToken("admin@example.com", null, List.of());

		AppUser user = AppUser.builder().username("admin@example.com").displayName("Admin").role(Role.ADMIN).build();

		when(appUserRepository.findByUsername("admin@example.com")).thenReturn(Optional.of(user));

		Assertions.assertThat(advice.currentUser(authenticated)).isSameAs(user);
	}

	@Test
	void isAdminShouldReturnFalseWhenNotAuthenticated() {
		AppViewModelAdvice advice = advice("1.0.0");

		Assertions.assertThat(advice.isAdmin(null)).isFalse();
	}

	@Test
	void isAdminShouldReturnTrueOnlyForRoleAdminAuthority() {
		AppViewModelAdvice advice = advice("1.0.0");

		Authentication admin = new UsernamePasswordAuthenticationToken("admin", null,
				List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
		Authentication user = new UsernamePasswordAuthenticationToken("user", null,
				List.of(new SimpleGrantedAuthority("ROLE_USER")));

		Assertions.assertThat(advice.isAdmin(admin)).isTrue();
		Assertions.assertThat(advice.isAdmin(user)).isFalse();
	}

	private AppViewModelAdvice advice(String version) {
		return new AppViewModelAdvice(version, userPagePreferenceService, appSettingService, inventoryWatchService,
				appUserRepository, new UpdateCheckService(Optional::empty, _ -> {
				}, Clock.systemDefaultZone()));
	}
}