package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Whether this run should restart itself with administrator rights so the USN
 * journal is available.
 *
 * <p>
 * The journal is the preferred way to track a library, and the reason is what
 * it keeps rather than what it costs: it is a durable record on the volume, so
 * changes made while the application was closed can still be read afterwards.
 * Real-time events live in the memory of a process that was not running, and a
 * later reconciliation only compares two snapshots - neither can say what
 * happened in between. Reading the journal needs a volume handle, and Windows
 * gives one only to an administrator.
 *
 * <p>
 * Five conditions, and all of them have to hold. Each is here because without
 * it the restart is either useless or harmful:
 *
 * <ol>
 * <li>allowed by configuration - the way out for anyone who would rather have
 * no prompt than have the journal;</li>
 * <li>Windows - the journal exists nowhere else;</li>
 * <li>a launcher to restart - {@code jpackage.app-path} exists only in an
 * installed copy, which is also the only thing that can be started again. It is
 * what keeps a UAC prompt out of the IDE, the Maven build and the test
 * suite;</li>
 * <li>no attempt yet, marked on the command line of the restart - otherwise a
 * volume that stays unreadable even to an administrator (a network drive, a
 * filesystem with no journal) restarts the application forever;</li>
 * <li>the volume is not already readable - being administrator already is the
 * common case for a developer, and prompting then would be asking for something
 * already held.</li>
 * </ol>
 */
public final class UsnElevation {

	/** Read before Spring exists, so it is a raw property rather than bound. */
	public static final String ENABLED_PROPERTY = "nimbus-file-manager.inventory.usn.elevate-on-start";

	/** Passed to the restarted process; its presence is what stops a loop. */
	public static final String ATTEMPTED_ARGUMENT = "--usn-elevation-attempted";

	private static final String WINDOWS = "windows";

	private UsnElevation() {
	}

	/**
	 * @param volumeReadable asked last, and only once every condition before it
	 * holds. It is a supplier rather than a {@code boolean} because answering it
	 * means opening a volume handle through kernel32, and merely loading that
	 * library away from Windows throws - so the question must not be reachable off
	 * the platform that has one. As a value it sat in the caller's argument list,
	 * where Java evaluates it before this method can rule the platform out; the
	 * {@code ExceptionInInitializerError} that produced is an {@code Error}, so the
	 * caller's own {@code catch} did not hold it either, and it killed {@code main}
	 * before Spring on every Linux and macOS start.
	 */
	public static boolean shouldRelaunch(boolean enabled, String operatingSystem, String launcher,
			boolean alreadyAttempted, BooleanSupplier volumeReadable) {
		return enabled && isWindows(operatingSystem) && launcher != null && !launcher.isBlank() && !alreadyAttempted
				&& !volumeReadable.getAsBoolean();
	}

	/** Absent means on: the journal is the tracking this product prefers. */
	public static boolean enabled(String configured) {
		return configured == null || configured.isBlank() || Boolean.parseBoolean(configured.trim());
	}

	public static boolean attempted(String[] arguments) {
		for (String argument : arguments) {
			if (ATTEMPTED_ARGUMENT.equals(argument)) {
				return true;
			}
		}

		return false;
	}

	private static boolean isWindows(String operatingSystem) {
		return operatingSystem != null && operatingSystem.toLowerCase(Locale.ROOT).contains(WINDOWS);
	}
}