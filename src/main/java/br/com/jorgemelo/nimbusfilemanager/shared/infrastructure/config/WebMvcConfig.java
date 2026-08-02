package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import br.com.jorgemelo.nimbusfilemanager.backup.infrastructure.web.RestoreInProgressInterceptor;
import br.com.jorgemelo.nimbusfilemanager.security.application.PasswordChangeRequiredInterceptor;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web.LibraryConfigurationInterceptor;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

	private static final String SCREENS = "/app/**";
	private static final String API = "/api/**";

	private final RestoreInProgressInterceptor restoreInProgressInterceptor;
	private final LibraryConfigurationInterceptor libraryConfigurationInterceptor;
	private final PasswordChangeRequiredInterceptor passwordChangeRequiredInterceptor;

	public WebMvcConfig(RestoreInProgressInterceptor restoreInProgressInterceptor,
			LibraryConfigurationInterceptor libraryConfigurationInterceptor,
			PasswordChangeRequiredInterceptor passwordChangeRequiredInterceptor) {
		this.restoreInProgressInterceptor = restoreInProgressInterceptor;
		this.libraryConfigurationInterceptor = libraryConfigurationInterceptor;
		this.passwordChangeRequiredInterceptor = passwordChangeRequiredInterceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		// First of the three: while the catalog is being replaced, every table is
		// momentarily absent, so the two below would query a database that is not
		// there to answer.
		registry.addInterceptor(restoreInProgressInterceptor).addPathPatterns(SCREENS, API);
		// The restore is reachable before a library exists on purpose: the folder to
		// watch is inside the backup, so requiring one first would mean scanning a
		// library to be allowed to replace it.
		registry.addInterceptor(libraryConfigurationInterceptor).addPathPatterns(SCREENS, API)
				.excludePathPatterns("/app/onboarding", "/app/onboarding/**", "/app/account", "/app/account/**",
						"/app/settings/folders", "/app/settings/backup/restore");
		registry.addInterceptor(passwordChangeRequiredInterceptor).addPathPatterns(SCREENS, API)
				.excludePathPatterns("/app/account", "/app/account/password");
	}
}