package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Which quarantine records currently have no file behind them.
 *
 * <p>
 * A reading, and only a reading. It exists so the screen can ask the question
 * without holding the capability to act on the answer: what it produces is a
 * shortlist to queue, and the worker looks again - under the lock, immediately
 * before removing anything - because a drive that was briefly unavailable makes
 * every item look absent at once.
 *
 * <p>
 * Asking before queueing also keeps the executions screen honest. A pass that
 * would clear nothing is not queued at all, so the history stays a list of
 * things that happened rather than of times somebody clicked a button.
 */
@Service
public class QuarantineAbsenceScan {

	private final MovementRepository movementRepository;

	public QuarantineAbsenceScan(MovementRepository movementRepository) {
		this.movementRepository = movementRepository;
	}

	/** The quarantined items whose file is not in the quarantine folder now. */
	@Transactional(readOnly = true)
	public List<UUID> absent() {
		return movementRepository
				.findByStatusAndReasonInOrderByIdDesc(MovementStatus.MOVED, QuarantineConstants.QUARANTINED_REASONS,
						PageRequest.of(0, QuarantineConstants.MAX_PER_RUN))
				.getContent().stream()
				.filter(movement -> !Files.exists(PathUtils.normalizePath(movement.getRequestedTargetPath())))
				.map(Movement::getMovementPublicId).toList();
	}
}