package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.LocalDateTime;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.security.domain.enums.Role;
import br.com.jorgemelo.nimbusfilemanager.security.domain.model.AppUser;
import br.com.jorgemelo.nimbusfilemanager.security.domain.repository.AppUserRepository;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * Opens every screen of the application against the real context and templates.
 * A Thymeleaf expression is not compiled and not checked at startup: it is
 * evaluated while the page renders, so an expression that cannot be evaluated -
 * a fragment parameter referenced inside a SpEL selection, a model attribute
 * nobody publishes, an accessor that no longer exists - ships through a green
 * build, coverage at the floor and a clean static analysis, and then breaks the
 * whole page on the first click.
 *
 * <p>
 * Each screen must answer {@code 200}, never merely "not an error". A blank
 * installation redirects every screen to onboarding
 * ({@link LibraryConfigurationInterceptor}), so a smoke test that accepted a
 * redirect would pass without rendering a single template - which is exactly
 * what this test did until the library below was configured.
 *
 * <p>
 * The catalog is left empty on purpose: every screen has to survive an
 * installation with nothing inventoried, which is the state of a first run and
 * the one least likely to be exercised by hand.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AppScreenSmokeIntegrationTest {

	private static final String ADMIN = "admin@nimbus.test";
	private static final String USER = "user@nimbus.test";

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AppUserRepository appUserRepository;

	@Autowired
	private AppSettingService appSettingService;

	@Autowired
	private ExecutionRepository executionRepository;

	/**
	 * A configured library and a real row for the signed-in user: without the first
	 * every screen answers a redirect instead of a page, and without the second the
	 * account screen fails for a reason that has nothing to do with what is under
	 * test.
	 */
	@BeforeEach
	void configureTheInstallation(@TempDir Path library) {
		save(ADMIN, Role.ADMIN);
		save(USER, Role.USER);

		appSettingService.update(SettingsConstants.WATCH_FOLDER, library.toString(), "system");
	}

	private void save(String username, Role role) {
		if (appUserRepository.findByUsername(username).isPresent()) {
			return;
		}

		appUserRepository.save(AppUser.builder().username(username).passwordHash("{noop}irrelevant")
				.displayName(username).role(role).enabled(true).build());
	}

	private void renders(String url) throws Exception {
		MvcResult result = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn();

		Assertions.assertThat(result.getResolvedException()).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "/app", "/app/timeline", "/app/map", "/app/files", "/app/organization", "/app/duplicates",
			"/app/quarantine", "/app/conversion", "/app/statistics", "/app/settings", "/app/settings/preferences",
			"/app/users", "/app/accesses", "/app/account" })
	@WithMockUser(username = ADMIN, roles = { "ADMIN", "USER" })
	void everyScreenRendersForAnAdministrator(String url) throws Exception {
		renders(url);
	}

	/**
	 * The partials each screen fetches on its own go through the same engine and
	 * fail the same way - and, arriving by JavaScript after the page is already up,
	 * a break in one of them is even easier to miss by hand.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "/app/files/items", "/app/executions/items", "/app/conversion/progress" })
	@WithMockUser(username = ADMIN, roles = { "ADMIN", "USER" })
	void everyPartialRendersForAnAdministrator(String url) throws Exception {
		renders(url);
	}

	/**
	 * The operational screens have to open for a plain user too. The role hierarchy
	 * is configuration, not code, so only a request proves it still holds.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "/app", "/app/timeline", "/app/map", "/app/account", "/app/settings/preferences" })
	@WithMockUser(username = USER, roles = "USER")
	void theOperationalScreensRenderForANonAdministrator(String url) throws Exception {
		renders(url);
	}

	/**
	 * And the administrative ones stay shut for that same user: a screen that opens
	 * for everyone is a worse defect than one that fails to open.
	 */
	@Test
	@WithMockUser(username = USER, roles = "USER")
	void theAdministrationScreensStayClosedForANonAdministrator() throws Exception {
		mockMvc.perform(get("/app/users")).andExpect(status().isForbidden());
	}

	/**
	 * The comparison screen needs two executions to compare; with fewer it returns
	 * to the list. Both halves are here because only the second renders that
	 * template, and only the first documents why the screen is not in the list
	 * above.
	 */
	@Test
	@WithMockUser(username = ADMIN, roles = { "ADMIN", "USER" })
	void theComparisonScreenRendersForTwoExecutionsAndReturnsToTheListForFewer() throws Exception {
		mockMvc.perform(get("/app/statistics/compare")).andExpect(status().is3xxRedirection());

		String ids = finishedExecution() + "," + finishedExecution();

		renders("/app/statistics/compare?ids=" + ids);
	}

	private long finishedExecution() {
		return executionRepository.save(Execution.builder().executionType(ExecutionType.INVENTORY)
				.status(ExecutionStatus.FINISHED).startedAt(LocalDateTime.now()).finishedAt(LocalDateTime.now())
				.sourcePath("D:/library").recursive(true).executeFlag(true).filesFound(0).filesAnalyzed(0).cacheHits(0)
				.filesMoved(0).simulatedFiles(0).errors(0).statusMessage(StatusMessage.raw("done")).build()).getId();
	}

	/**
	 * The first run: with no library configured every screen is sent to onboarding,
	 * and onboarding itself is the one page that renders. This is also what makes
	 * the assertions above meaningful - it is the redirect they must not accept.
	 */
	@Test
	@WithMockUser(username = ADMIN, roles = { "ADMIN", "USER" })
	void anInstallationWithoutALibraryRendersOnboardingAndSendsEveryScreenToIt() throws Exception {
		appSettingService.update(SettingsConstants.WATCH_FOLDER, "", "system");

		renders("/app/onboarding");

		mockMvc.perform(get("/app/files")).andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/app/onboarding"));
	}
}