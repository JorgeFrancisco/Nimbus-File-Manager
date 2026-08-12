package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.FileSystemChange;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.WatchRecoveryReason;

/**
 * The outcome of a one-shot USN startup catch-up: the files changed while the
 * application was down (empty when nothing changed or the cursor could not be
 * replayed), plus why - if at all - a full reconcile is still needed.
 *
 * <p>
 * A reason rather than a flag, because the two cases that produce one are
 * different facts about the journal and used to be indistinguishable once they
 * reached the watcher.
 *
 * @param offlineChanges what changed while the app was down.
 * @param recoveryReason why the catalog must be reconciled anyway, or
 * {@code null} when the replay accounted for the whole window.
 */
public record UsnCatchUpResult(List<FileSystemChange> offlineChanges, WatchRecoveryReason recoveryReason) {

	public UsnCatchUpResult {
		offlineChanges = offlineChanges == null ? List.of() : List.copyOf(offlineChanges);
	}
}