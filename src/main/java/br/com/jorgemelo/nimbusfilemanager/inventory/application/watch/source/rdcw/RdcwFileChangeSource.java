package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.FileChangeSource;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.WatchRecoveryReason;

/**
 * The Windows real-time {@link FileChangeSource}, backed by
 * {@code ReadDirectoryChangesW} with {@code bWatchSubtree=TRUE}. It holds a
 * <b>single</b> directory handle on the root - no per-subdirectory handles - so
 * it never blocks Explorer from moving library folders, detects changes
 * recursively in real time, and needs <b>no elevation</b> (a directory read
 * handle, not the volume device).
 *
 * <p>
 * The optional USN catch-up (available only when the volume can be opened, i.e.
 * elevated) is folded in as a one-shot: the offline changes it found are
 * delivered on the first poll, then the source is pure real-time. When no
 * catch-up was possible, a startup reconcile is requested so offline changes
 * are still picked up.
 */
public class RdcwFileChangeSource implements FileChangeSource {

	private final Path root;
	private final RdcwReadSeam seam;
	private final RdcwChangeInterpreter interpreter;

	private List<Path> pendingCatchUp;
	private WatchRecoveryReason startupReason;
	private boolean overflow;

	public RdcwFileChangeSource(Path root, RdcwReadSeam seam, List<Path> catchUpChanges,
			WatchRecoveryReason startupReason) {
		this.root = root.toAbsolutePath().normalize();
		this.seam = seam;
		this.interpreter = new RdcwChangeInterpreter(this.root);
		this.pendingCatchUp = catchUpChanges == null ? List.of() : List.copyOf(catchUpChanges);
		this.startupReason = startupReason;
	}

	@Override
	public List<Path> pollChangedFiles() {
		List<Path> changed = new ArrayList<>(pendingCatchUp);
		pendingCatchUp = List.of();

		RdcwReadResult result = seam.poll();

		overflow |= result.overflowed();
		changed.addAll(interpreter.interpret(result.relativePaths()));

		return changed;
	}

	/**
	 * The journal replay, and never a live notification: the two arrive by
	 * different roads and only this one is old enough for a full walk to have
	 * covered it. The directory handle is opened and armed before the replay runs,
	 * so a change made in between is seen by both - reported twice, which costs a
	 * poll, and never missed.
	 */
	@Override
	public List<Path> takeOfflineBacklog() {
		List<Path> backlog = pendingCatchUp;

		pendingCatchUp = List.of();

		return backlog;
	}

	/**
	 * The live overflow is reported ahead of the one-shot startup reason when both
	 * are pending, because it is the one about now - and both ask for the same
	 * recovery, so nothing is lost by reporting either.
	 */
	@Override
	public Optional<WatchRecoveryReason> consumeRecoveryReason() {
		WatchRecoveryReason reason = overflow ? WatchRecoveryReason.EVENTS_LOST : startupReason;

		overflow = false;
		startupReason = null;

		return Optional.ofNullable(reason);
	}

	@Override
	public Path root() {
		return root;
	}

	@Override
	public void close() {
		seam.close();
	}
}