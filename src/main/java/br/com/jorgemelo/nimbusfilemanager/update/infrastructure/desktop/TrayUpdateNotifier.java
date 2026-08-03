package br.com.jorgemelo.nimbusfilemanager.update.infrastructure.desktop;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.desktop.ApplicationTray;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.UpdateFound;

/**
 * Puts a found version on the tray icon.
 *
 * <p>
 * The bridge exists because the check cannot call the tray itself: it lives in
 * {@code application}, which does not know {@code infrastructure} - and because
 * on a machine with no tray at all (a container, a Linux server) this simply
 * does nothing, while the check goes on working.
 */
@Component
public class TrayUpdateNotifier {

	@EventListener
	public void onUpdateFound(UpdateFound event) {
		ApplicationTray.updateAvailable(event.published());
	}
}