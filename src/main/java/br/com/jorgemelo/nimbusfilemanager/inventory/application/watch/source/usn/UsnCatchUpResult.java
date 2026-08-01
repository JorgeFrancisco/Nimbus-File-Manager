package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

import java.nio.file.Path;
import java.util.List;

/**
 * The outcome of a one-shot USN startup catch-up: the files changed while the
 * application was down (empty when nothing changed or the cursor could not be
 * replayed), plus whether a full reconcile is still needed - because the cursor
 * could not be replayed, or a directory moved within the offline window.
 *
 * @param offlineChanges the files changed while the app was down.
 * @param reconcileNeeded whether the catalog must be reconciled anyway.
 */
public record UsnCatchUpResult(List<Path> offlineChanges, boolean reconcileNeeded) {

	public UsnCatchUpResult {
		offlineChanges = offlineChanges == null ? List.of() : List.copyOf(offlineChanges);
	}
}