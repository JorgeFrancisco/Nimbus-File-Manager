package br.com.jorgemelo.nimbusfilemanager.shared.application;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhaseType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * Single localization point for execution type and status labels, shared by the
 * execution read model ({@code ExecutionMapper}) and the statistics screens,
 * which render raw telemetry projections and need the same labels by enum. The
 * switches use literal bundle keys (not a computed prefix + enum name) so every
 * key stays visible to the i18n key-parity test.
 */
@Component
public class ExecutionLabels extends LocalizedComponent {

	public String type(ExecutionType type) {
		return switch (type) {
		case INVENTORY -> message("backend.execution.type.INVENTORY");
		case ORGANIZATION -> message("backend.execution.type.ORGANIZATION");
		case UNDO -> message("backend.execution.type.UNDO");
		case EXPORT -> message("backend.execution.type.EXPORT");
		case SUMMARY -> message("backend.execution.type.SUMMARY");
		case DEDUP_DELETE -> message("backend.execution.type.DEDUP_DELETE");
		case RECONCILE -> message("backend.execution.type.RECONCILE");
		case CONVERSION -> message("backend.execution.type.CONVERSION");
		};
	}

	public String status(ExecutionStatus status) {
		return switch (status) {
		case STARTED -> message("backend.execution.status.started");
		case SCANNING_FILES -> message("backend.execution.status.scanning");
		case PROCESSING_FILES -> message("backend.execution.status.processing");
		case FINISHED -> message("backend.execution.status.finished");
		case FINISHED_WITH_ERRORS -> message("backend.execution.status.finishedWithErrors");
		case INTERRUPTED -> message("backend.execution.status.interrupted");
		case ERROR -> message("backend.execution.status.error");
		case CANCELLED -> message("backend.execution.status.cancelled");
		case REJECTED -> message("backend.execution.status.rejected");
		};
	}

	private String phase(ExecutionPhaseType phase) {
		return switch (phase) {
		case SCAN_COUNT -> message("enum.executionPhaseType.SCAN_COUNT");
		case CACHE_CHECK -> message("enum.executionPhaseType.CACHE_CHECK");
		case EXTRACTION -> message("enum.executionPhaseType.EXTRACTION");
		case PERSISTENCE -> message("enum.executionPhaseType.PERSISTENCE");
		case PLAN -> message("enum.executionPhaseType.PLAN");
		case MOVEMENT -> message("enum.executionPhaseType.MOVEMENT");
		case RECONCILE -> message("enum.executionPhaseType.RECONCILE");
		};
	}

	/** Every type label by enum, for views that render raw telemetry rows. */
	public Map<ExecutionType, String> types() {
		Map<ExecutionType, String> labels = new EnumMap<>(ExecutionType.class);

		for (ExecutionType type : ExecutionType.values()) {
			labels.put(type, type(type));
		}

		return labels;
	}

	/** Every status label by enum, for views that render raw telemetry rows. */
	public Map<ExecutionStatus, String> statuses() {
		Map<ExecutionStatus, String> labels = new EnumMap<>(ExecutionStatus.class);

		for (ExecutionStatus status : ExecutionStatus.values()) {
			labels.put(status, status(status));
		}

		return labels;
	}

	/** Every phase label by enum, for views that render raw telemetry phases. */
	public Map<ExecutionPhaseType, String> phases() {
		Map<ExecutionPhaseType, String> labels = new EnumMap<>(ExecutionPhaseType.class);

		for (ExecutionPhaseType phase : ExecutionPhaseType.values()) {
			labels.put(phase, phase(phase));
		}

		return labels;
	}
}