package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationPreviewExportService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationService;
import br.com.jorgemelo.nimbusfilemanager.organization.infrastructure.rest.OrganizationController;
import br.com.jorgemelo.nimbusfilemanager.security.application.AppLogoutSuccessHandler;
import br.com.jorgemelo.nimbusfilemanager.security.application.AppUserDetailsService;
import br.com.jorgemelo.nimbusfilemanager.security.application.LoginFailureHandler;
import br.com.jorgemelo.nimbusfilemanager.security.application.LogoutAwareAccessDeniedHandler;
import br.com.jorgemelo.nimbusfilemanager.security.application.OAuth2LoginSuccessHandler;
import br.com.jorgemelo.nimbusfilemanager.security.application.TwoFactorAuthenticationSuccessHandler;
import br.com.jorgemelo.nimbusfilemanager.security.domain.repository.AppUserRepository;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web.AppViewModelAdvice;

@WebMvcTest(controllers = OrganizationController.class, properties = "nimbus-file-manager.security.google-login-enabled=false", excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
		WebMvcConfig.class, LocaleConfig.class, AppViewModelAdvice.class }))
// The real LogoutAwareAccessDeniedHandler (no dependencies) is imported alongside
// SecurityConfig so the slice context loads and access-denied still yields a real 403
// (a mock handler would swallow it and break the isForbidden assertions).
@Import({ SecurityConfig.class, LogoutAwareAccessDeniedHandler.class })
class SecurityConfigTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OrganizationService organizationService;

	@MockitoBean
	private OrganizationPreviewExportService organizationPreviewExportService;

	@MockitoBean
	private AppSettingService appSettingService;

	@MockitoBean
	private AppUserRepository appUserRepository;

	@MockitoBean
	private AppUserDetailsService appUserDetailsService;

	@MockitoBean
	private TwoFactorAuthenticationSuccessHandler successHandler;

	@MockitoBean
	private LoginFailureHandler loginFailureHandler;

	@MockitoBean
	private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

	@MockitoBean
	private AppLogoutSuccessHandler appLogoutSuccessHandler;

	@Test
	void organizationExecuteIsOperationalForAnyUser() throws Exception {
		when(organizationService.execute(any())).thenReturn(null);

		String body = """
				{"sourcePath":"C:/workspace/source","targetPath":"C:/workspace/target"}
				""";

		// Anonymous is bounced to login; a POST without a CSRF token is rejected; a
		// logged-in
		// USER may execute, because organizing the collection is a normal operation -
		// not admin.
		mockMvc.perform(
				post("/api/organization/execute").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().is3xxRedirection());
		mockMvc.perform(post("/api/organization/execute").with(user("user").roles("USER"))
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
		mockMvc.perform(post("/api/organization/execute").with(user("user").roles("USER")).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
	}

	@Test
	void organizationUndoIsOperationalForAnyUser() throws Exception {
		// Missing CSRF is rejected; a logged-in USER may undo an organization run.
		mockMvc.perform(post("/api/organization/execute/00000000-0000-7000-8000-000000000001/undo")
				.with(user("user").roles("USER"))).andExpect(status().isForbidden());
		mockMvc.perform(post("/api/organization/execute/00000000-0000-7000-8000-000000000001/undo")
				.with(user("user").roles("USER")).with(csrf())).andExpect(status().isOk());
	}

	@Test
	void previouslyCsrfExcludedApiMutationsNowRequireToken() throws Exception {
		String body = """
				{"sourcePath":"C:/workspace/source","targetPath":"C:/workspace/target"}
				""";

		// preview and preview/export are operational (USER) mutations that used to fall
		// under the
		// blanket /api/** CSRF exclusion. They ride the same form-login session, so a
		// missing
		// token must be rejected (403) before the request ever reaches a handler.
		for (String path : new String[] { "/api/organization/preview", "/api/organization/preview/export" }) {
			mockMvc.perform(
					post(path).with(user("user").roles("USER")).contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isForbidden());
		}
	}

	@Test
	void mediaApiShouldRequireLogin() throws Exception {
		String url = "/api/media/00000000-0000-7000-8000-000000000001/thumbnail";

		// Anonymous is bounced to login; any authenticated user gets through security
		// (404 here only
		// because the media controller isn't part of this slice).
		mockMvc.perform(get(url)).andExpect(status().is3xxRedirection());
		mockMvc.perform(get(url).with(user("user").roles("USER"))).andExpect(status().isNotFound());
	}

	@Test
	void dataApisShouldRequireLogin() throws Exception {
		// The data APIs used to be anonymous under the blanket /api/** permitAll; now
		// every /api/**
		// route (other than the OpenAPI docs) requires a logged-in session.
		for (String url : new String[] { "/api/catalog", "/api/duplicates", "/api/executions", "/api/metadata",
				"/api/statistics", "/api/timeline" }) {
			mockMvc.perform(get(url)).andExpect(status().is3xxRedirection());
		}

		// A logged-in user clears security (404 only because these controllers aren't
		// in this slice).
		mockMvc.perform(get("/api/timeline").with(user("user").roles("USER"))).andExpect(status().isNotFound());
	}

	@Test
	void operationalWebRoutesAreOpenToUser() throws Exception {
		// The former "admin-only" screens are normal operational features of the single
		// shared
		// collection. A logged-in USER is not forbidden (404 here only because these
		// controllers
		// are outside this @WebMvcTest slice; the point is that security lets the
		// request pass).
		for (String path : new String[] { "/app/files", "/app/files/items", "/app/statistics", "/app/timeline",
				"/app/organization", "/app/quarantine", "/app/duplicates", "/app/settings/folders" }) {
			mockMvc.perform(get(path).with(user("user").roles("USER"))).andExpect(status().isNotFound());
		}
	}

	@Test
	void adminOnlyRoutesAreForbiddenForUserAndAllowedForAdmin() throws Exception {
		// Administration, global configuration, auditing and technical diagnostics stay
		// ADMIN-only.
		for (String path : new String[] { "/app/users", "/app/accesses", "/app/settings", "/actuator/metrics" }) {
			mockMvc.perform(get(path).with(user("user").roles("USER"))).andExpect(status().isForbidden());
			mockMvc.perform(get(path).with(user("admin").roles("ADMIN"))).andExpect(status().isNotFound());
		}

		// Global technical reprocessing (POST): USER is forbidden even with a valid
		// CSRF token.
		for (String path : new String[] { "/api/metadata/rebuild", "/app/duplicates/phash/rebuild" }) {
			mockMvc.perform(post(path).with(user("user").roles("USER")).with(csrf())
					.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
			mockMvc.perform(post(path).with(user("admin").roles("ADMIN")).with(csrf())
					.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
		}
	}

	@Test
	void adminInheritsUserPermissionsViaRoleHierarchy() throws Exception {
		// ROLE_ADMIN > ROLE_USER: an admin satisfies the hasRole("USER") operational
		// rules without
		// holding ROLE_USER explicitly. Without the hierarchy these requests would be
		// 403.
		for (String path : new String[] { "/app/files", "/app/statistics", "/app/quarantine", "/app/duplicates" }) {
			mockMvc.perform(get(path).with(user("admin").roles("ADMIN"))).andExpect(status().isNotFound());
		}
	}
}