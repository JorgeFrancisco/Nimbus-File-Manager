package br.com.jorgemelo.nimbusfilemanager.shared.application.library;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MoveBaseline;

/**
 * The capacity to change a file the user owns.
 *
 * <p>
 * Not a file-system utility: every operation here is one the product performs
 * on somebody's photos and videos, and each carries the two guarantees that
 * come with that - the change is announced to the folder watcher before it
 * happens, so the application does not go looking for work it did itself, and a
 * move is verified byte for byte against a baseline taken before it started.
 * Whoever holds this interface can do those things; whoever does not, cannot.
 *
 * <p>
 * The methods are deliberately specific. A generic {@code mutate(Path, ...)}
 * would be {@code Files} with extra steps: it would let a caller do anything
 * while looking like it went through the port, and the guarantees above would
 * become something each caller remembers or forgets. Adding an operation here
 * is meant to be a decision, and the shape of it is meant to say what the
 * operation means.
 *
 * <p>
 * Deliberately silent about the workspace. Thumbnails, temporary conversions,
 * downloaded datasets and the embedded database are this product's own
 * artefacts: they are regenerable, no watcher cares about them, and routing
 * them through here would drown the one thing this exists to protect.
 */
public interface LibraryFileMutations {

	/**
	 * Moves a file the user owns, verifying the moved bytes against a baseline
	 * captured before the move. A verification failure throws without rolling
	 * back, because what to do about it differs per caller - some have a database
	 * row to undo first.
	 *
	 * @param executionId the execution this move belongs to, or {@code null} for
	 * one nobody queued. Naming it is what lets a move that outlasts the
	 * announcement ceiling go on being recognised as this product's own work
	 * @return the size and SHA-256 the target is now proved to contain. Handing it
	 * back costs nothing - the bytes were read to verify the move - and spares a
	 * caller that needs the digest a third full read of the file
	 */
	MoveBaseline move(Path source, Path target, boolean overwrite, Long executionId) throws IOException;

	/**
	 * Best-effort physical move-back, for a caller whose own post-move step
	 * failed.
	 *
	 * @return false if the file could not be put back
	 */
	boolean rollback(Path from, Path to);

	/**
	 * Renames a directory. Separate from {@link #move} because a directory has no
	 * bytes of its own to verify - the guarantee a move makes would be a promise
	 * about nothing - while the announcement to the watcher matters just as much.
	 *
	 * @param executionId the execution responsible, or {@code null}. It matters
	 * more here than anywhere: renaming a folder moves everything under it in one
	 * operating-system call, and the watcher sees a notification for every one of
	 * those files
	 */
	void renameDirectory(Path source, Path target, Long executionId) throws IOException;

	/**
	 * Deletes one file the user owns.
	 *
	 * @param executionId the execution responsible, or {@code null}
	 */
	void deleteFile(Path path, Long executionId) throws IOException;

	/**
	 * Deletes a directory only if it is empty, which is what every caller of this
	 * actually wants: the folder left behind after its contents moved or were
	 * purged. It never recurses, so it cannot become "delete this tree" by
	 * accident.
	 */
	void deleteEmptyDirectory(Path path, Long executionId) throws IOException;

	/**
	 * Carries a timestamp onto a file already in place. It is the one mutation
	 * here that changes no bytes and moves nothing, and it is here for the same
	 * reason as the others: the watcher sees it, and a converted video that
	 * arrived with today's date would land in the wrong place on the timeline.
	 */
	void carryModifiedTime(Path path, FileTime modifiedTime, Long executionId);
}