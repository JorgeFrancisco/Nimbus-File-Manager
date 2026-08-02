package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.desktop;

import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import br.com.jorgemelo.nimbusfilemanager.shared.application.TrayText;

/**
 * The application's presence on the desktop while it runs with no window of its
 * own.
 *
 * <p>
 * It exists for the two things a console was doing. The first is being visible
 * at all: a first start spends minutes fetching a database server before
 * anything is listening, and without a sign of life that reads as a program
 * that failed to open - so the icon is installed from {@code main}, before
 * Spring, and says what the bootstrap is doing. The second is stopping
 * properly: the embedded PostgreSQL is shut down by the application's own
 * shutdown, and until now the only way to reach it was Ctrl+C in a window the
 * user had to keep open. Ending from the task manager left the server running.
 *
 * <p>
 * Static because it has to be reachable from {@code main}, from the bootstrap
 * progress and from a Spring bean, at moments when only one of the three
 * exists. Every method is a no-op where there is no desktop - a container, a
 * Linux server, a session with no tray - so the caller never asks first.
 *
 * <p>
 * The menu opens things through {@code explorer.exe} rather than
 * {@link java.awt.Desktop}: this process may be running elevated to read the
 * USN journal, and a browser started from it would inherit that. Handing the
 * target to the shell gets it opened by the user who is logged in, at their own
 * privilege.
 */
public final class ApplicationTray {

	private static final String ICON_RESOURCE = "/static/img/nimbus-file-manager-icon.png";

	/**
	 * Reached by full path rather than by name: what opens a folder must not be a
	 * decision left to the {@code PATH} of whoever started the application.
	 */
	private static final String SYSTEM_ROOT_VARIABLE = "SystemRoot";
	private static final String DEFAULT_SYSTEM_ROOT = "C:\\Windows";
	private static final String SHELL = "explorer.exe";

	private static final AtomicReference<TrayIcon> icon = new AtomicReference<>();
	private static final AtomicReference<MenuItem> openItem = new AtomicReference<>();
	private static final AtomicReference<Runnable> shutdown = new AtomicReference<>();
	private static final AtomicInteger port = new AtomicInteger();
	private static final AtomicBoolean announced = new AtomicBoolean();

	private ApplicationTray() {
	}

	/**
	 * @param logs where the log file is written
	 * @param workspace everything the installation owns
	 */
	public static void install(Path logs, Path workspace) {
		if (!SystemTray.isSupported()) {
			return;
		}

		try {
			TrayText text = new TrayText(Locale.getDefault());

			TrayIcon trayIcon = new TrayIcon(image(), text.get("tray.tooltip.starting"), menu(text, logs, workspace));

			trayIcon.setImageAutoSize(true);

			// The double click is what Windows treats as the icon's default action, and
			// it is the gesture people try first. It reaches the same guard as the menu
			// item: before there is a port, there is nothing to open.
			trayIcon.addActionListener(_ -> open());

			SystemTray.getSystemTray().add(trayIcon);

			icon.set(trayIcon);
		} catch (Exception _) {
			// A missing tray is not a reason to refuse to start; everything below is a
			// no-op from here, and the application runs exactly as it did before.
			icon.set(null);
		}
	}

	/**
	 * What the bootstrap is doing, for as long as there is nothing to open.
	 *
	 * <p>
	 * The tooltip takes every step; a notification is raised only for the first,
	 * because that is the one nobody is waiting for. A first start fetches a
	 * database server before anything listens, and somebody who has just
	 * double-clicked an icon has no reason to suspect there is a tray icon at all,
	 * let alone to hover it. Every step after that would be noise - the balloon
	 * has already said the application is working.
	 */
	public static void status(String message) {
		TrayIcon trayIcon = icon.get();

		if (trayIcon == null) {
			return;
		}

		trayIcon.setToolTip(message);

		if (announced.compareAndSet(false, true)) {
			notify(trayIcon, text().get("tray.tooltip.starting"), message);
		}
	}

	/** The port is what the first menu item needs, so it arrives with it. */
	public static void ready(int servedPort, Runnable gracefulShutdown) {
		port.set(servedPort);

		shutdown.set(gracefulShutdown);

		TrayIcon trayIcon = icon.get();

		if (trayIcon == null) {
			return;
		}

		TrayText message = text();

		trayIcon.setToolTip(message.get("tray.tooltip.running"));

		MenuItem open = openItem.get();

		if (open != null) {
			open.setEnabled(true);
		}

		// Raised unconditionally: this is the moment the application became usable,
		// and it carries the address that opens it.
		notify(trayIcon, message.get("tray.tooltip.running"),
				MessageFormat.format(message.get("tray.ready"), TrayText.url(servedPort)));
	}

	public static void remove() {
		TrayIcon trayIcon = icon.getAndSet(null);

		if (trayIcon != null) {
			SystemTray.getSystemTray().remove(trayIcon);
		}
	}

	private static PopupMenu menu(TrayText text, Path logs, Path workspace) {
		PopupMenu menu = new PopupMenu();

		MenuItem open = new MenuItem(text.get("tray.open"));

		// Nothing to open until something is listening, and a menu item that does
		// nothing is worse than one that says it is not ready.
		open.setEnabled(false);

		open.addActionListener(_ -> open());

		openItem.set(open);

		MenuItem logsItem = new MenuItem(text.get("tray.logs"));

		logsItem.addActionListener(_ -> shell(logs.toString()));

		MenuItem workspaceItem = new MenuItem(text.get("tray.workspace"));

		workspaceItem.addActionListener(_ -> shell(workspace.toString()));

		MenuItem exit = new MenuItem(text.get("tray.exit"));

		exit.addActionListener(_ -> exit());

		version(text).ifPresent(item -> {
			menu.add(item);
			menu.addSeparator();
		});

		menu.add(open);
		menu.add(logsItem);
		menu.add(workspaceItem);
		menu.addSeparator();
		menu.add(exit);

		return menu;
	}

	/**
	 * Which build is running, for the question that gets asked whenever something
	 * looks wrong - and it has to be answerable without opening the application,
	 * since "it will not open" is when it is asked. Absent outside a packaged
	 * build: the manifest that carries it is written by the jar, so an IDE run has
	 * no version to show and shows no item rather than the word "unknown".
	 */
	private static Optional<MenuItem> version(TrayText text) {
		String version = ApplicationTray.class.getPackage().getImplementationVersion();

		if (version == null || version.isBlank()) {
			return Optional.empty();
		}

		MenuItem item = new MenuItem(MessageFormat.format(text.get("tray.version"), version));

		// It states, it does not act.
		item.setEnabled(false);

		return Optional.of(item);
	}

	/**
	 * Silent before the port is known. The menu item is disabled until then, but
	 * the double click cannot be, and opening the address of port 0 would answer a
	 * gesture with a browser error.
	 */
	private static void open() {
		int servedPort = port.get();

		if (servedPort > 0) {
			shell(TrayText.url(servedPort));
		}
	}

	/**
	 * The graceful path when Spring is up - which is what stops the embedded
	 * PostgreSQL - and a plain exit before that, when there is nothing to close.
	 */
	private static void exit() {
		remove();

		Runnable graceful = shutdown.get();

		if (graceful != null) {
			graceful.run();

			return;
		}

		System.exit(0);
	}

	private static void notify(TrayIcon trayIcon, String caption, String message) {
		try {
			trayIcon.displayMessage(caption, message, TrayIcon.MessageType.INFO);
		} catch (Exception _) {
			// A desktop that refuses notifications still has the tooltip and the menu.
		}
	}

	private static TrayText text() {
		return new TrayText(Locale.getDefault());
	}

	private static void shell(String target) {
		String root = System.getenv(SYSTEM_ROOT_VARIABLE);

		Path explorer = Path.of(root == null || root.isBlank() ? DEFAULT_SYSTEM_ROOT : root, SHELL);

		try {
			new ProcessBuilder(explorer.toString(), target).start();
		} catch (Exception _) {
			// Opening a folder is a convenience; failing at it must not end anything.
		}
	}

	private static Image image() throws IOException {
		try (InputStream stream = ApplicationTray.class.getResourceAsStream(ICON_RESOURCE)) {
			if (stream == null) {
				return Toolkit.getDefaultToolkit().createImage(new byte[0]);
			}

			return ImageIO.read(stream);
		}
	}
}