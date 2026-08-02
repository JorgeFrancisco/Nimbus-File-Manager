package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto;

import org.springframework.boot.context.properties.bind.DefaultValue;

public record Security(int idleTimeoutMinutes, int maxFailedLoginAttempts, int lockoutDurationMinutes,
		@DefaultValue("true") boolean googleLoginEnabled, @DefaultValue("admin") String defaultUsername,
		@DefaultValue("admin") String defaultPassword, String resetPassword) {
}