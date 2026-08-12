package br.com.jorgemelo.nimbusfilemanager.telemetry.infrastructure.config;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;

/**
 * Which kinds of execution keep their measurements.
 *
 * <p>
 * Not every execution is worth a telemetry row. A rename writes one file and a
 * quarantine restore moves a handful; measuring them would fill the history
 * with rows nobody compares. The kinds listed here are the ones whose cost is
 * the question - a library-wide walk and the two fingerprint drains.
 *
 * <p>
 * Typed as {@code Set<ExecutionType>} so an unknown name fails at startup
 * rather than becoming a type that silently never matches. An empty set means
 * nothing is persisted, which is a valid answer and the one a diagnosis run
 * uses.
 */
@ConfigurationProperties(prefix = "nimbus-file-manager.execution-metrics")
public class ExecutionMetricsProperties {

	private Set<ExecutionType> persistedTypes = EnumSet.of(ExecutionType.INVENTORY, ExecutionType.FINGERPRINT_PHOTO,
			ExecutionType.FINGERPRINT_VIDEO);

	public Set<ExecutionType> getPersistedTypes() {
		return persistedTypes;
	}

	public void setPersistedTypes(Set<ExecutionType> persistedTypes) {
		this.persistedTypes = persistedTypes == null ? EnumSet.noneOf(ExecutionType.class) : persistedTypes;
	}

	/** Whether a run of this kind writes its measurements down when it ends. */
	public boolean persists(ExecutionType type) {
		return type != null && persistedTypes.contains(type);
	}
}