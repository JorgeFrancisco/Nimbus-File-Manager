package br.com.jorgemelo.nimbusfilemanager.security.application.dto;

import java.time.LocalDateTime;

/**
 * One access-log row already resolved for the screen: {@code eventTypeLabel},
 * {@code statusLabel} and {@code messageLabel} carry the localized text, while
 * {@code statusCode} keeps the raw {@code SUCCESS}/{@code FAILURE} code so the
 * template picks the badge style by code, never by translated text.
 */
public record AccessLogView(String username, String eventTypeLabel, String statusLabel, String statusCode,
		String messageLabel, String ipAddress, LocalDateTime createdAt) {
}