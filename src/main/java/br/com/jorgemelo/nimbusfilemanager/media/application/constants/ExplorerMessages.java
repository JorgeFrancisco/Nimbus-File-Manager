package br.com.jorgemelo.nimbusfilemanager.media.application.constants;

import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;

/**
 * Everything the Files screen's three commands can say, as a key and its
 * arguments.
 *
 * <p>
 * Never as resolved text. The commands are carried out in the worker, which has
 * no request behind it and therefore no language; and the same sentence is
 * sometimes answered straight to the screen, where there is one. Kept as a code,
 * it is the same sentence either way, localized wherever it is read.
 */
public final class ExplorerMessages {

	/**
	 * What a queued command says about itself while it waits. No arguments, so
	 * these are referenced as codes rather than through a factory.
	 */
	public static final String RENAME_STARTED = "backend.files.renameStarted";

	public static final String QUARANTINE_STARTED = "backend.files.quarantineStarted";

	public static final String DELETE_STARTED = "backend.files.deleteStarted";

	private ExplorerMessages() {
	}

	public static ExecutionMessage libraryNotConfigured() {
		return of("backend.files.libraryNotConfigured");
	}

	public static ExecutionMessage pathGone() {
		return of("backend.files.pathGone");
	}

	public static ExecutionMessage notPhysical() {
		return of("backend.files.notPhysical");
	}

	public static ExecutionMessage outsideLibrary(String libraryRoot) {
		return of("backend.files.outsideLibrary", libraryRoot);
	}

	public static ExecutionMessage quarantineNotConfigured() {
		return of("backend.files.quarantineNotConfigured");
	}

	public static ExecutionMessage nothingCatalogued() {
		return of("backend.files.quarantineNothingCataloged");
	}

	public static ExecutionMessage quarantineDone(int moved, int kept, int failed) {
		return of("backend.files.quarantineDone", moved, kept, failed);
	}

	public static ExecutionMessage quarantineDoneFolderKept(int moved) {
		return of("backend.files.quarantineDoneFolderKept", moved);
	}

	public static ExecutionMessage emptyFolderRemoved() {
		return of("backend.files.emptyFolderRemoved");
	}

	public static ExecutionMessage folderNotRemoved() {
		return of("backend.files.folderNotRemoved");
	}

	public static ExecutionMessage deleteDone(int deleted) {
		return of("backend.files.deleteDone", deleted);
	}

	public static ExecutionMessage deleteFailed(String detail) {
		return of("backend.files.deleteFailed", detail);
	}

	public static ExecutionMessage renameDone(String newName) {
		return of("backend.files.renameDone", newName);
	}

	public static ExecutionMessage renameFailed(String detail) {
		return of("backend.files.renameFailed", detail);
	}

	public static ExecutionMessage renameTargetExists(String newName) {
		return of("backend.files.renameTargetExists", newName);
	}

	public static ExecutionMessage renameInvalidName() {
		return of("backend.files.renameInvalidName");
	}

	/**
	 * The two answers a command gives when it outlived the screen's budget. Both
	 * mean accepted: the first that something is carrying it out, the second that
	 * nothing is, yet - the request is durable either way and neither is a refusal.
	 */
	public static ExecutionMessage stillProcessing() {
		return of("backend.files.stillProcessing");
	}

	public static ExecutionMessage waitingForWorker() {
		return of("backend.files.waitingForWorker");
	}

	private static ExecutionMessage of(String code, Object... args) {
		return new ExecutionMessage(code, List.of(args));
	}
}