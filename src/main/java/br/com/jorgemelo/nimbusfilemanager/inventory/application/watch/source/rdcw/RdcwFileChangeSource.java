package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.FileChangeSource;

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
	private boolean startupReconcile;
	private boolean overflow;

	public RdcwFileChangeSource(Path root, RdcwReadSeam seam, List<Path> catchUpChanges, boolean reconcileOnStartup) {
		this.root = root.toAbsolutePath().normalize();
		this.seam = seam;
		this.interpreter = new RdcwChangeInterpreter(this.root);
		this.pendingCatchUp = catchUpChanges == null ? List.of() : List.copyOf(catchUpChanges);
		this.startupReconcile = reconcileOnStartup;
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

	@Override
	public boolean consumeOverflow() {
		boolean happened = overflow || startupReconcile;

		overflow = false;
		startupReconcile = false;

		return happened;
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