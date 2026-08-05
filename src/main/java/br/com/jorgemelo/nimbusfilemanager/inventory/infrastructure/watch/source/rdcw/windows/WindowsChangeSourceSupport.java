package br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.rdcw.windows;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.FileChangeSource;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw.RdcwFileChangeSource;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw.RdcwReadSeam;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw.RdcwUnavailableException;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn.UsnCatchUpResult;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn.UsnCursorStore;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.WatchRecoveryReason;
import br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.usn.windows.WindowsUsnSupport;
import lombok.extern.slf4j.Slf4j;

/**
 * The single Windows entry point that assembles the change source for a root:
 * {@code ReadDirectoryChangesW} for recursive real-time events (single handle,
 * no elevation, no folder lock), plus a one-shot USN journal catch-up for the
 * changes made while the app was down when the volume can be opened (elevated).
 *
 * <ul>
 * <li>The recursive watch is the floor: if it cannot be opened the caller falls
 * back to the per-directory {@code WatchService} (a
 * {@link RdcwUnavailableException} propagates).</li>
 * <li>USN catch-up is best-effort: without privilege it is skipped and a
 * startup reconcile is requested instead, so real-time watching still works
 * with no elevation.</li>
 * </ul>
 */
@Slf4j
public final class WindowsChangeSourceSupport {

	private WindowsChangeSourceSupport() {
	}

	public static FileChangeSource open(Path root, UsnCursorStore cursorStore, int bufferBytes) {
		Path normalized = root.toAbsolutePath().normalize();

		RdcwReadSeam seam = FfmRdcwReadSeam.open(normalized, bufferBytes);

		try {
			Optional<UsnCatchUpResult> catchUp = WindowsUsnSupport.tryCatchUp(normalized, cursorStore, bufferBytes);

			if (catchUp.isPresent()) {
				log.info("Change source for {}: ReadDirectoryChangesW (real-time) + USN journal catch-up", normalized);

				describe(normalized, catchUp.get().recoveryReason());

				return new RdcwFileChangeSource(normalized, seam, catchUp.get().offlineChanges(),
						catchUp.get().recoveryReason());
			}

			log.info("Change source for {}: ReadDirectoryChangesW (real-time) only; USN catch-up unavailable "
					+ "(no elevation) - a startup reconcile covers offline changes", normalized);

			return new RdcwFileChangeSource(normalized, seam, List.of(),
					WatchRecoveryReason.JOURNAL_UNREPLAYABLE);
		} catch (RuntimeException | LinkageError failure) {
			seam.close();

			throw failure;
		}
	}

	/**
	 * Says in the log which of the journal's two failure modes was met, and says
	 * nothing when neither was. A replay that covered its whole window is the
	 * ordinary case and deserves no line of its own.
	 */
	private static void describe(Path root, WatchRecoveryReason reason) {
		if (reason == null) {
			return;
		}

		switch (reason) {
		case JOURNAL_UNREPLAYABLE -> log.info("USN journal for {} could not be replayed from the stored cursor "
				+ "(absent, recreated or aged out): a reconciliation covers the offline window", root);
		case JOURNAL_REPLAY_INCOMPLETE -> log.info("USN replay for {} could not account for every record in its "
				+ "window: a reconciliation covers the difference", root);
		case EVENTS_LOST -> log.warn("USN catch-up for {} reported lost events", root);
		}
	}
}