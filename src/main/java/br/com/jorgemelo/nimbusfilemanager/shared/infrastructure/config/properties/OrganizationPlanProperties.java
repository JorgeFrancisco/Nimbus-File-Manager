package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long a published organization plan stays around.
 *
 * <p>
 * The plan is not history - the execution that produced it is, and that keeps
 * the executions screen's retention. The plan exists for somebody to look at
 * and decide, so it has its own, much shorter life: a default of half a day
 * covers a working session and a return to it the same day, and after that the
 * user asks for a fresh preview rather than acting on a picture of a library
 * that has moved on.
 *
 * <p>
 * The number is tuning, not architecture. What is architectural is that the
 * expiry is a column - a plan's validity is decided from stored state, so it
 * survives every restart and means the same thing to both processes.
 */
@ConfigurationProperties(prefix = "nimbus-file-manager.organization.plan")
public record OrganizationPlanProperties(Integer ttlHours) {

	private static final Logger log = LoggerFactory.getLogger(OrganizationPlanProperties.class);

	public static final int DEFAULT_TTL_HOURS = 12;
	public static final int MIN_TTL_HOURS = 1;
	public static final int MAX_TTL_HOURS = 168;

	public int ttlHoursOrDefault() {
		if (ttlHours == null) {
			return DEFAULT_TTL_HOURS;
		}

		if (ttlHours < MIN_TTL_HOURS || ttlHours > MAX_TTL_HOURS) {
			log.warn("Ignoring nimbus-file-manager.organization.plan.ttl-hours={}: outside [{}, {}]. Using {}.",
					ttlHours, MIN_TTL_HOURS, MAX_TTL_HOURS, DEFAULT_TTL_HOURS);

			return DEFAULT_TTL_HOURS;
		}

		return ttlHours;
	}
}