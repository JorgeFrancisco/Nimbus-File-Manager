package br.com.jorgemelo.nimbusfilemanager.time.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;

/**
 * Application-wide {@link Clock} whose zone tracks the
 * {@code nimbus-file-manager.timezone} setting live: {@link #getZone()} reads
 * the current configuration on every call, so changing the time zone on the
 * settings screen takes effect without a restart. The instant is always the
 * real {@link Instant#now()}; only the zone is configurable, which keeps the
 * stored {@code LocalDateTime}s in the configured zone exactly as before
 * (default {@code America/Sao_Paulo}).
 */
@Component
public class ConfigurableClock extends Clock {

	private final AppSettingService appSettingService;

	public ConfigurableClock(AppSettingService appSettingService) {
		this.appSettingService = appSettingService;
	}

	@Override
	public ZoneId getZone() {
		return appSettingService.zoneId();
	}

	@Override
	public Instant instant() {
		return Instant.now();
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return Clock.system(zone);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}

		if (!(obj instanceof ConfigurableClock other)) {
			return false;
		}

		return Objects.equals(appSettingService, other.appSettingService);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(appSettingService);
	}
}