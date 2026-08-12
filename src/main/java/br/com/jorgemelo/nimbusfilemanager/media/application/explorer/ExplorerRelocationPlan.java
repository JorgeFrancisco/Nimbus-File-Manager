package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.MovementRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PreparedMovement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.MovementWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * What an Explorer relocation is about to do, written down before it does it.
 *
 * <p>
 * The Explorer used to move the file and only then tell the catalog, giving the
 * fact the identity of the execution that ordered it - which is the identity of
 * a command rather than of something that happened, and which a folder rename
 * cannot even supply, having one execution and a fact per file. A movement per
 * catalogued file, prepared first, answers both: it carries the identity its
 * fact will be written under, and it is what a retry finds instead of working
 * out from the disk what it was in the middle of.
 *
 * <p>
 * A file nobody catalogued reserves nothing. The user is free to rename it and
 * the disk will obey; there is simply no catalogued file for a movement to be
 * about, and inventing one would put a row in the history for something the
 * catalog has never had an opinion on.
 */
@Component
public class ExplorerRelocationPlan {

	private final CatalogFileLocationRepository catalogFileLocationRepository;
	private final MovementWriter movementWriter;

	public ExplorerRelocationPlan(CatalogFileLocationRepository catalogFileLocationRepository,
			MovementWriter movementWriter) {
		this.catalogFileLocationRepository = catalogFileLocationRepository;
		this.movementWriter = movementWriter;
	}

	/**
	 * @return one prepared movement per catalogued file that will move, empty when
	 * the catalog knows nothing about what is being renamed. Asking twice under the
	 * same execution returns the same rows, identities included: the write refuses a
	 * second row for a file it already prepared one for
	 */
	public List<PreparedMovement> reserve(Execution execution, Path source, Path target, boolean directory) {
		List<MovementRequest> requests = directory ? underFolder(source, target) : atPath(source, target);

		if (requests.isEmpty()) {
			// Either nothing catalogued was there, or this run already moved it and the
			// catalog has stopped naming the source - which are the same answer to a
			// question asked of the catalog, and different answers entirely. The
			// operations this execution reserved tell them apart, and they were written
			// down before anything moved for exactly this reason.
			return movementWriter.reserved(execution.getId());
		}

		return movementWriter.prepare(execution.getId(), requests);
	}

	/** The operation happened; every movement it prepared is settled at once. */
	public void settle(Execution execution, List<PreparedMovement> reserved) {
		if (reserved.isEmpty()) {
			return;
		}

		movementWriter.markMoved(execution.getId(), publicIds(reserved));
	}

	/**
	 * The operation did not happen. The movements say so rather than being left
	 * pending, which is what would let a later reader believe the disk had moved.
	 */
	public void abandon(Execution execution, List<PreparedMovement> reserved, MovementReason reason) {
		if (reserved.isEmpty()) {
			return;
		}

		movementWriter.markFailed(execution.getId(), publicIds(reserved), reason);
	}

	private List<MovementRequest> atPath(Path source, Path target) {
		return catalogFileLocationRepository
				.findPresentByPath(PathUtils.normalize(source), PathFlavor.of(source).name()).map(CatalogFile::getId)
				.map(id -> List.of(new MovementRequest(id, source, target, MovementReason.NONE)))
				.orElseGet(List::of);
	}

	/**
	 * Where each file under the folder will be, worked out the way the folder move
	 * itself works it out: what sits below the old root is untouched and only the
	 * root in front of it changes. What is recorded is the target the operation
	 * asked for, which is what the column says it holds - the door that writes the
	 * location stays the authority on what it turns out to be.
	 */
	private List<MovementRequest> underFolder(Path source, Path target) {
		String oldRoot = PathUtils.normalize(source);
		String newRoot = PathUtils.normalize(target);

		return catalogFileLocationRepository.findPlacementsUnderFolder(oldRoot, PathFlavor.of(source).name())
				.stream()
				.map(row -> new MovementRequest(row.getCatalogFileId(), Path.of(row.getCurrentPath()),
						Path.of(newRoot + row.getCurrentPath().substring(oldRoot.length())), MovementReason.NONE))
				.toList();
	}

	private static List<UUID> publicIds(List<PreparedMovement> reserved) {
		return reserved.stream().map(PreparedMovement::movementPublicId).toList();
	}
}