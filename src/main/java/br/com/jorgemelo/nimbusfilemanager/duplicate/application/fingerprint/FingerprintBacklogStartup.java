package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;

/**
 * Asks for both backlogs once, when the application is up.
 *
 * <p>
 * It replaces two startup beans that did more than this and had to: each marked
 * every run left {@code RUNNING} as failed and then started a drain guarded by
 * an {@code AtomicBoolean}. Both belonged to the application alone, and the
 * Javadoc of the older one said why - a guard one JVM cannot show another, and a
 * recovery that would fail the live run of whoever else owned it.
 *
 * <p>
 * Neither is needed now. A run interrupted by a hard stop is reclaimed by the
 * queue's lease, and a second drain is refused by the deduplication index, which
 * both processes can see. All that is left is the asking.
 */
@Component
@Profile(NimbusProfiles.APP)
public class FingerprintBacklogStartup {

	private final FingerprintBacklogLauncher fingerprintBacklogLauncher;

	public FingerprintBacklogStartup(FingerprintBacklogLauncher fingerprintBacklogLauncher) {
		this.fingerprintBacklogLauncher = fingerprintBacklogLauncher;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void askForWhatIsPending() {
		fingerprintBacklogLauncher.launchBoth();
	}
}