package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryWatchStatus;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.InventoryWatchService;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.security.domain.model.AppUser;
import br.com.jorgemelo.nimbusfilemanager.security.domain.repository.AppUserRepository;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.update.application.UpdateCheckService;

/**
 * Shared model attributes for server-rendered pages: authenticated vs
 * anonymous/unauthenticated behaviour of each @ModelAttribute and the settings-
 * backed values.
 */
class AppViewModelAdviceExtraTest {

	private final UserPagePreferenceService userPagePreferenceService = mock(UserPagePreferenceService.class);
	private final AppSettingService appSettingService = mock(AppSettingService.class);
	private final InventoryWatchService inventoryWatchService = mock(InventoryWatchService.class);
	private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
	private final AppViewModelAdvice advice = new AppViewModelAdvice("2.0.0", userPagePreferenceService,
			appSettingService, inventoryWatchService, appUserRepository,
			new UpdateCheckService(Optional::empty, _ -> {
			}, Clock.systemDefaultZone()));

	private final Authentication user = new TestingAuthenticationToken("bob", "x", "ROLE_USER");
	private final Authentication anonymous = new TestingAuthenticationToken("anonymousUser", "x", "ROLE_ANONYMOUS");

	@Test
	void appVersionAndIdleTimeoutComeFromConfiguration() {
		when(appSettingService.intValue(SettingsConstants.IDLE_TIMEOUT_MINUTES, 5)).thenReturn(7);

		Assertions.assertThat(advice.appVersion()).isEqualTo("2.0.0");
		Assertions.assertThat(advice.idleTimeoutMinutes()).isEqualTo(7);
	}

	@Test
	void libraryConfiguredReflectsWatchFolder() {
		when(appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn("C:/media");

		Assertions.assertThat(advice.libraryConfigured()).isTrue();

		when(appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn("   ");

		Assertions.assertThat(advice.libraryConfigured()).isFalse();
	}

	@Test
	void inventoryWatchIsUnconfiguredForUnauthenticatedUsers() {
		InventoryWatchStatus status = InventoryWatchStatus.unconfigured();

		when(inventoryWatchService.status()).thenReturn(status);

		Assertions.assertThat(advice.inventoryWatch(user)).isSameAs(status);
		Assertions.assertThat(advice.inventoryWatch(null)).isEqualTo(InventoryWatchStatus.unconfigured());
		Assertions.assertThat(advice.inventoryWatch(anonymous)).isEqualTo(InventoryWatchStatus.unconfigured());
	}

	@Test
	void sidebarCollapsedReadsLayoutPreference() {
		when(userPagePreferenceService.find("bob", "layout")).thenReturn(Map.of("sidebar-collapsed", "true"));

		Assertions.assertThat(advice.sidebarCollapsed(user)).isTrue();
		Assertions.assertThat(advice.sidebarCollapsed(null)).isFalse();
	}

	@Test
	void themeDefaultsToLightAndReadsDarkPreference() {
		when(userPagePreferenceService.find("bob", "layout")).thenReturn(Map.of("theme", "dark"));

		Assertions.assertThat(advice.theme(user)).isEqualTo("dark");
		Assertions.assertThat(advice.theme(null)).isEqualTo("light");
	}

	/** A user who never chose a theme sees the light one, not an empty value. */
	@Test
	void themeIsLightForAUserWhoNeverChoseOne() {
		when(userPagePreferenceService.find("bob", "layout")).thenReturn(Map.of());

		Assertions.assertThat(advice.theme(user)).isEqualTo("light");
	}

	@Test
	void currentUserResolvedFromRepositoryWhenAuthenticated() {
		AppUser appUser = AppUser.builder().username("bob").build();

		when(appUserRepository.findByUsername("bob")).thenReturn(Optional.of(appUser));

		Assertions.assertThat(advice.currentUser(user)).isSameAs(appUser);
		Assertions.assertThat(advice.currentUser(null)).isNull();
	}

	@Test
	void isAdminOnlyForAdminAuthority() {
		Authentication admin = new TestingAuthenticationToken("boss", "x", "ROLE_ADMIN");

		Assertions.assertThat(advice.isAdmin(admin)).isTrue();
		Assertions.assertThat(advice.isAdmin(user)).isFalse();
		Assertions.assertThat(advice.isAdmin(null)).isFalse();
	}
}