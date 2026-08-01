package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import br.com.jorgemelo.nimbusfilemanager.security.application.AppLogoutSuccessHandler;
import br.com.jorgemelo.nimbusfilemanager.security.application.AppUserDetailsService;
import br.com.jorgemelo.nimbusfilemanager.security.application.LoginFailureHandler;
import br.com.jorgemelo.nimbusfilemanager.security.application.LogoutAwareAccessDeniedHandler;
import br.com.jorgemelo.nimbusfilemanager.security.application.OAuth2LoginSuccessHandler;
import br.com.jorgemelo.nimbusfilemanager.security.application.TwoFactorAuthenticationSuccessHandler;
import br.com.jorgemelo.nimbusfilemanager.security.domain.enums.Role;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;

@Configuration
public class SecurityConfig {

	private static final Logger LOGGER = LoggerFactory.getLogger(SecurityConfig.class);

	private static final String LOGIN_PATH = "/login";

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, TwoFactorAuthenticationSuccessHandler successHandler,
			LoginFailureHandler loginFailureHandler, OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler,
			AppLogoutSuccessHandler appLogoutSuccessHandler,
			LogoutAwareAccessDeniedHandler logoutAwareAccessDeniedHandler, NimbusFileManagerProperties properties,
			@Value("${spring.security.oauth2.client.registration.google.client-id:}") String googleClientId,
			@Value("${spring.security.oauth2.client.registration.google.client-secret:}") String googleClientSecret)
			throws Exception {
		boolean googleLoginEnabled = properties.security().googleLoginEnabled();

		http.authorizeHttpRequests(auth -> auth
				// Public entry points and static assets (login, registration, OpenAPI docs,
				// health probe). Everything else requires a logged-in session.
				.requestMatchers("/error", LOGIN_PATH, "/login/2fa", "/change-password", "/register", "/confirm",
						"/favicon.ico", "/css/**", "/js/**", "/img/**", "/.well-known/**", "/swagger-ui.html",
						"/swagger-ui/**", "/v3/api-docs/**")
				.permitAll().requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
				// Administrative surfaces (ADMIN only): user/role management and access
				// auditing, plus the operational screens whose data is admin-scoped (files,
				// organization, duplicates, quarantine, conversion, statistics) together with
				// their dedicated data/export APIs. The shared read APIs that also feed the
				// USER-facing timeline/map lightbox (media, map, timeline) stay operational
				// under the USER fallback below.
				.requestMatchers("/app/users/**", "/app/accesses/**").hasRole(Role.ADMIN.name())
				.requestMatchers("/app/files/**", "/app/organization/**", "/app/duplicates/**",
						"/app/quarantine/**", "/app/conversion/**", "/app/statistics/**")
				.hasRole(Role.ADMIN.name())
				.requestMatchers("/api/organization/**", "/api/duplicates/**", "/api/statistics/**",
						"/api/catalog/**", "/api/files/**", "/api/diagnostics/**").hasRole(Role.ADMIN.name())
				// Personal preferences and the shared folder picker are operational even though
				// they live under /app/settings; the rest of Settings is global system
				// configuration and maintenance, so it stays ADMIN.
				.requestMatchers("/app/settings/preferences", "/app/settings/folders").hasRole(Role.USER.name())
				.requestMatchers("/app/settings/**").hasRole(Role.ADMIN.name())
				// Global technical reprocessing (ADMIN only): metadata rebuild.
				.requestMatchers(HttpMethod.POST, "/api/metadata/rebuild").hasRole(Role.ADMIN.name())
				// Actuator (other than the public health probe) exposes technical diagnostics.
				.requestMatchers("/actuator/**").hasRole(Role.ADMIN.name())
				// Everything else is a normal operational feature over the single shared
				// collection - dashboard, timeline, map, execution history, the shared
				// media/map/timeline read APIs (which also feed the lightbox), and the user's
				// own account and preferences. Any authenticated USER may use it; the role
				// hierarchy below lets ADMIN inherit all of it.
				.anyRequest().hasRole(Role.USER.name()))
				// CSRF stays at Spring Security's default (enabled for every state-changing
				// request). /api/** is authenticated by the same form-login session - it is not
				// a stateless token/API-key surface - so its POST/PUT/PATCH/DELETE must carry a
				// CSRF token like any other mutation. The only exposed actuator endpoints
				// (health/info/metrics) are read-only GETs, which CSRF never guards, so no
				// exclusion is needed there either.
				.formLogin(form -> form.loginPage(LOGIN_PATH).successHandler(successHandler)
						.failureHandler(loginFailureHandler).permitAll())
				.logout(logout -> logout.logoutUrl("/logout").logoutSuccessHandler(appLogoutSuccessHandler).permitAll())
				// A CSRF-rejected POST /logout (typically a stale token from a tab left open
				// across a
				// restart) becomes a real logout + redirect to /login, instead of a raw 403.
				// Every
				// other access denial keeps the default 403 behaviour.
				.exceptionHandling(exception -> exception.accessDeniedHandler(logoutAwareAccessDeniedHandler))
				// Spring Security's default is X-Frame-Options: DENY, which blocks the PDF/TXT
				// preview <iframe> in the Arquivos lightbox (files.html) even though it only
				// ever
				// points at this same app's own /app/files/preview endpoint. SAMEORIGIN keeps
				// the
				// clickjacking protection against other origins while allowing that same-origin
				// case.
				.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));

		if (googleLoginEnabled && googleLoginConfigured(googleClientId, googleClientSecret)) {
			http.oauth2Login(oauth -> oauth.loginPage(LOGIN_PATH).successHandler(oAuth2LoginSuccessHandler));
		} else if (googleLoginEnabled) {
			LOGGER.warn("Google login is enabled, but client-id/client-secret are not configured. "
					+ "The application will start with Google login unavailable.");
		}

		return http.build();
	}

	private boolean googleLoginConfigured(String googleClientId, String googleClientSecret) {
		return googleClientId != null && !googleClientId.isBlank() && googleClientSecret != null
				&& !googleClientSecret.isBlank();
	}

	@Bean
	RoleHierarchy roleHierarchy() {
		// ADMIN inherits every USER permission. This lets operational rules be written
		// as hasRole("USER") while administrators satisfy them without a separate
		// ROLE_USER grant, keeping the "ADMIN can do everything USER can" contract in a
		// single place.
		return RoleHierarchyImpl.withDefaultRolePrefix().role(Role.ADMIN.name()).implies(Role.USER.name()).build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	DaoAuthenticationProvider authenticationProvider(AppUserDetailsService userDetailsService,
			PasswordEncoder passwordEncoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);

		provider.setPasswordEncoder(passwordEncoder);

		return provider;
	}
}