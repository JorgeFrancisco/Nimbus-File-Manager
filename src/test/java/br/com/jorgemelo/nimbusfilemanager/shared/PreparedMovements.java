package br.com.jorgemelo.nimbusfilemanager.shared;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.MovementRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PreparedMovement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

/**
 * Movements as the write door hands them back: prepared, pending, and already
 * carrying the identity of the fact they will produce.
 *
 * <p>
 * That last part is the reason this exists rather than a plain constructor call
 * with two random UUIDs. The identity of the operation and the identity of the
 * fact it will record are different things - the movement is what we are doing,
 * the event is what happened - and the whole point of preparing before touching
 * the disk is that both are decided while walking away is still free. A test
 * that mints them at the point of assertion proves nothing about that ordering.
 *
 * <p>
 * So they are minted here, distinct and both v7, and a test that cares can hold
 * on to either and check it survived the operation unchanged.
 */
public final class PreparedMovements {

	/** A movement about to take a file from one place to another. */
	public static PreparedMovement pending(long id, Long catalogFileId, Path source, Path target) {
		return new PreparedMovement(id, UuidV7.generate(), UuidV7.generate(), catalogFileId, real(source),
				real(target), MovementStatus.PENDING);
	}

	/**
	 * The path a movement carries, refusing a relative one.
	 *
	 * <p>
	 * A movement is what several capabilities read to decide where to move a file,
	 * and they normalise what they read - so a relative path here does not stay a
	 * label: it becomes an absolute path under whatever directory the tests happen
	 * to run from, which is the project itself. A test that named its ends
	 * {@code "source"} and {@code "target"} therefore drove a real move over the
	 * build output and renamed {@code target} to {@code source} nineteen seconds
	 * into a suite run, taking every class off the classpath and turning the rest
	 * of the run into hundreds of unrelated-looking failures.
	 *
	 * <p>
	 * Refused here rather than in each test, because the trap is invisible at the
	 * call site and the damage is done before any assertion runs.
	 */
	private static String real(Path path) {
		if (!path.isAbsolute()) {
			throw new IllegalArgumentException(
					"A prepared movement needs a real path: " + path + " is relative and would be resolved "
							+ "against the working directory. Use @TempDir.");
		}

		return PathUtils.normalize(path);
	}

	/**
	 * What the write door answers for a whole batch: one pending operation per
	 * request, in the order they were asked for.
	 *
	 * <p>
	 * A run that prepares nothing gets nothing back, and a caller that finds no
	 * operation for its file leaves the file alone - so a test that forgets this
	 * does not fail at the door, it fails much later, looking like the move itself
	 * was refused.
	 */
	public static List<PreparedMovement> pendingFor(List<MovementRequest> requests) {
		List<PreparedMovement> prepared = new ArrayList<>();

		for (MovementRequest request : requests) {
			prepared.add(pending(prepared.size() + 1L, request.catalogFileId(), request.requestedSource(),
					request.requestedTarget()));
		}

		return prepared;
	}

	/**
	 * An operation that already finished, which is what a reclaimed execution is
	 * handed back for the files its previous attempt got through.
	 *
	 * <p>
	 * The difference from {@link #pending} is the whole reason preparing is
	 * idempotent: the same file, gone from its source and sitting at its target,
	 * means "resume me" while the operation is pending and "somebody already did
	 * this" once it has settled. Nothing on disk tells them apart.
	 */
	public static PreparedMovement settled(long id, Long catalogFileId, Path source, Path target) {
		return new PreparedMovement(id, UuidV7.generate(), UuidV7.generate(), catalogFileId, real(source),
				real(target), MovementStatus.MOVED);
	}

	/**
	 * A movement whose fact identity the test wants to name, for the cases that
	 * follow it through the door and back.
	 */
	public static PreparedMovement pending(long id, Long catalogFileId, Path source, Path target,
			UUID catalogFileEventPublicId) {
		return new PreparedMovement(id, UuidV7.generate(), catalogFileEventPublicId, catalogFileId, real(source),
				real(target), MovementStatus.PENDING);
	}

	private PreparedMovements() {
	}
}