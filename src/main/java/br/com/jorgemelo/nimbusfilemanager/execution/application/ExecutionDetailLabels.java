package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * Localized labels for the movement and analysis-error enums shown on the
 * execution-detail screen, resolved in the backend so the template renders the
 * audit tables without translating any domain code itself. The maps are keyed
 * by enum name, since the responses carry the name string; exhaustive switches
 * over literal bundle keys keep every key visible to the i18n key-parity test.
 */
@Component
public class ExecutionDetailLabels extends LocalizedComponent {

	public String movementStatus(MovementStatus status) {
		return switch (status) {
		case SIMULATED -> message("backend.movement.status.simulated");
		case MOVED -> message("backend.movement.status.moved");
		case SKIPPED -> message("backend.movement.status.skipped");
		case ERROR -> message("backend.movement.status.error");
		case UNDONE -> message("backend.movement.status.undone");
		case UNDO_ERROR -> message("backend.movement.status.undoError");
		};
	}

	public String movementReason(MovementReason reason) {
		return switch (reason) {
		case NONE -> message("backend.movement.reason.none");
		case TARGET_EXISTS -> message("backend.movement.reason.targetExists");
		case DUPLICATE_TARGET -> message("backend.movement.reason.duplicateTarget");
		case OVERWRITE_DISABLED -> message("backend.movement.reason.overwriteDisabled");
		case DUPLICATE_QUARANTINED -> message("backend.movement.reason.duplicateQuarantined");
		case CONVERTED_QUARANTINED -> message("backend.movement.reason.convertedQuarantined");
		case SOURCE_NOT_FOUND -> message("backend.movement.reason.sourceNotFound");
		case SOURCE_NOT_PHYSICAL -> message("backend.movement.reason.sourceNotPhysical");
		case ALREADY_MOVED -> message("backend.movement.reason.alreadyMoved");
		case ACCESS_DENIED -> message("backend.movement.reason.accessDenied");
		case IO_ERROR -> message("backend.movement.reason.ioError");
		case DATABASE_UPDATE_FAILED -> message("backend.movement.reason.databaseUpdateFailed");
		case INTEGRITY_CHECK_FAILED -> message("backend.movement.reason.integrityCheckFailed");
		case UNDONE_BY_USER -> message("backend.movement.reason.undoneByUser");
		case RESTORED_FROM_QUARANTINE -> message("backend.movement.reason.restoredFromQuarantine");
		case USER_QUARANTINED -> message("backend.movement.reason.userQuarantined");
		case USER_CANCELLED -> message("backend.movement.reason.userCancelled");
		};
	}

	public String executionErrorType(ExecutionErrorType type) {
		return switch (type) {
		case CRC_ERROR -> message("backend.executionError.type.crcError");
		case ACCESS_DENIED -> message("backend.executionError.type.accessDenied");
		case FILE_NOT_FOUND -> message("backend.executionError.type.fileNotFound");
		case HASH_ERROR -> message("backend.executionError.type.hashError");
		case METADATA_ERROR -> message("backend.executionError.type.metadataError");
		case CONVERSION_ERROR -> message("backend.executionError.type.conversionError");
		case MOVE_ERROR -> message("backend.executionError.type.moveError");
		case UNKNOWN -> message("backend.executionError.type.unknown");
		};
	}

	/** Every movement status label by enum name, for the audit tables. */
	public Map<String, String> movementStatuses() {
		Map<String, String> labels = new HashMap<>();

		for (MovementStatus status : MovementStatus.values()) {
			labels.put(status.name(), movementStatus(status));
		}

		return labels;
	}

	/** Every movement reason label by enum name, for the integrity summary. */
	public Map<String, String> movementReasons() {
		Map<String, String> labels = new HashMap<>();

		for (MovementReason reason : MovementReason.values()) {
			labels.put(reason.name(), movementReason(reason));
		}

		return labels;
	}

	/** Every analysis-error type label by enum name, for the errors table. */
	public Map<String, String> executionErrorTypes() {
		Map<String, String> labels = new HashMap<>();

		for (ExecutionErrorType type : ExecutionErrorType.values()) {
			labels.put(type.name(), executionErrorType(type));
		}

		return labels;
	}
}