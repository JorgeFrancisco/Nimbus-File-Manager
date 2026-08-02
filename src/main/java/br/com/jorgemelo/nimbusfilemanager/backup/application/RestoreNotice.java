package br.com.jorgemelo.nimbusfilemanager.backup.application;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.CatalogRestored;

/**
 * Holds, until somebody is shown it, the one thing a restore leaves the user
 * needing to know: the catalog now describes another machine's library, and
 * this machine's disk is about to be re-read.
 *
 * <p>
 * A restore re-points the watcher at the restored folder, and the first file
 * event on that drive starts a full re-inventory. Nothing is wrong with that -
 * it is the only way a catalog taken elsewhere becomes true here. What was
 * wrong is that it happened unannounced: a hundred thousand rows had just
 * landed, the fingerprint backlog had just started, and the disk went busy for
 * minutes with nothing on screen to say why.
 *
 * <p>
 * Shown once and then forgotten, deliberately. It is news about a moment, not a
 * state to track: a banner that outlived the moment would be one more thing on
 * screen that nobody can act on, and persisting it would mean deciding when it
 * stops being true - a question the reconcile pass answers on its own.
 */
@Component
public class RestoreNotice {

	private final AtomicReference<String> pending = new AtomicReference<>();

	@EventListener
	public void onCatalogRestored(CatalogRestored event) {
		pending.set(event.name());
	}

	/**
	 * The backup just restored, once. Reading it clears it, so the banner appears
	 * on the first screen drawn after the restore and on no other.
	 */
	public Optional<String> consume() {
		return Optional.ofNullable(pending.getAndSet(null));
	}
}