package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MoveBaseline;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * The single secure-move primitive shared by every feature that relocates a
 * real file on disk - organization, duplicate quarantine, and the undo of both.
 * It captures a SHA-256 baseline from the source, creates the target's parent
 * directories, moves the file, and verifies the target byte-for-byte against
 * the baseline, throwing {@link MoveIntegrityException} on any mismatch.
 *
 * <p>
 * Persistence and audit rows are the caller's responsibility (they differ per
 * feature); the caller wraps this call in its own transaction and applies
 * {@link #rollback} if its own post-move step fails, so the integrity guarantee
 * and the rollback primitive stay identical everywhere while the bookkeeping
 * stays local. This is the one place file-move safety, integrity and physical
 * rollback live - features never call {@code Files.move} directly.
 */
@Slf4j
@Component
public class SecureFileMove {

	private final OrganizationMoveVerifier verifier;
	private final SelfWrittenPathRegistry selfWrittenPathRegistry;

	public SecureFileMove(OrganizationMoveVerifier verifier, SelfWrittenPathRegistry selfWrittenPathRegistry) {
		this.verifier = verifier;
		this.selfWrittenPathRegistry = selfWrittenPathRegistry;
	}

	/**
	 * Captures the baseline, creates parents, moves {@code source} to
	 * {@code target} and verifies the moved file. An IO failure before the move
	 * leaves the source in place; a verification failure throws
	 * {@link MoveIntegrityException} <em>without</em> rolling back, so the caller
	 * applies its own rollback/audit policy (mirrors the organization executor).
	 *
	 * @return what the target now contains, proved rather than assumed: the size
	 * and SHA-256 the source had, which the verification has just shown the target
	 * to match
	 */
	public MoveBaseline move(Path source, Path target, boolean overwrite) throws IOException {
		return move(source, target, overwrite, null);
	}

	/**
	 * @param executionId the execution this move belongs to, or {@code null} for a
	 * move nobody queued. Naming it is what lets a move that outlasts the
	 * announcement ceiling - a very large file crossing volumes - go on being
	 * recognised as this product's own work for as long as the execution
	 * demonstrably still holds its paths
	 */
	public MoveBaseline move(Path source, Path target, boolean overwrite, Long executionId) throws IOException {
		MoveBaseline baseline = capture(source);

		// Both ends announced before the file actually moves, because the folder
		// watcher can poll the event milliseconds later: every move through here is
		// the application rearranging its own library, and it updates the catalog
		// itself, so the watcher has nothing to discover and no reason to re-scan the
		// tree. The registry runs the move so that a move that never happened takes
		// its announcement with it, rather than leaving both paths silenced.
		selfWrittenPathRegistry.move(executionId, () -> place(source, target, overwrite), source, target);

		verifier.verify(source, target, baseline);

		reportIfTheMoveLeftSomethingBehind(source, target);

		// Returned rather than discarded, which is the whole of this change. The bytes
		// were read twice to prove this value; a caller that needs the digest and does
		// not get it here reads them a third time, and until now every one of them
		// either did or went without.
		return baseline;
	}

	private static void place(Path source, Path target, boolean overwrite) throws IOException {
		Path parent = target.getParent();

		if (parent != null) {
			Files.createDirectories(parent);
		}

		if (overwrite) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		} else {
			Files.move(source, target);
		}
	}

	/**
	 * The baseline is read from the file itself, so anything holding it open fails
	 * here - before anything has moved. That is the normal case on a real desktop:
	 * a photo viewer, an antivirus, a sync client.
	 *
	 * <p>
	 * {@code FileHashService} reports an unreadable file as an
	 * {@link IllegalStateException}, which this method does not declare and its
	 * callers do not catch. The rename on the Files screen catches
	 * {@link IOException} and turned that into an error page instead of "the file
	 * is in use"; the organization executor survived only because it catches
	 * everything. Translating it back here keeps the promise the signature makes,
	 * for every caller at once.
	 */
	private MoveBaseline capture(Path source) throws IOException {
		try {
			return verifier.capture(source);
		} catch (IllegalStateException exception) {
			if (exception.getCause() instanceof IOException cause) {
				throw new IOException("Could not read " + source + " to capture the move baseline", cause);
			}

			throw exception;
		}
	}

	/**
	 * A move that returned without throwing must leave the file at the target and
	 * nothing at the source. Twice now a conversion ended with the encoded video
	 * back under its temporary name and the catalogued path empty, and there was
	 * nothing in the logs to say when or how - the move itself had reported
	 * success. Two {@code exists} calls turn the next occurrence into evidence of
	 * which end survived, which is the fact both investigations lacked.
	 */
	private void reportIfTheMoveLeftSomethingBehind(Path source, Path target) {
		boolean sourceSurvived = Files.exists(source);
		boolean targetMissing = !Files.exists(target);

		if (sourceSurvived || targetMissing) {
			log.warn("Move reported success but the file system disagrees: source={} stillThere={} target={}"
					+ " present={}", source, sourceSurvived, target, !targetMissing);
		}
	}

	/**
	 * Best-effort physical move-back after a failed post-move step. Returns false
	 * if it could not.
	 *
	 * <p>
	 * Announced in its own right rather than relying on the move it undoes. The
	 * two entries that move left behind do cover this one, and only because both
	 * ends are the same two paths - a coincidence, and not one worth a correct
	 * outcome depending on.
	 */
	public boolean rollback(Path from, Path to) {
		try {
			selfWrittenPathRegistry.move(null, () -> Files.move(from, to), from, to);

			return true;
		} catch (IOException e) {
			log.error("Could not roll back move from {} to {}", from, to, e);

			return false;
		}
	}
}