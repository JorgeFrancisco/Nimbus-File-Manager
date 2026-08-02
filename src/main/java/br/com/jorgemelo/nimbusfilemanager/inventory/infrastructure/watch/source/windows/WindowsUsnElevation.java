package br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.windows;

import java.lang.ProcessBuilder.Redirect;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn.UsnElevation;

/**
 * Restarts the application with administrator rights when that is what stands
 * between it and the USN journal.
 *
 * <p>
 * Runs from {@code main} before Spring, because the alternative is booting the
 * whole application and then throwing it away: the decision needs nothing from
 * the context, and taking it later would mean starting a database, a web server
 * and a watcher twice.
 *
 * <p>
 * Declining is a first-class answer. Windows returns a plain error when the
 * user says no to the prompt, and this reports it as "not relaunched" - the
 * caller then starts normally, without the journal, exactly as it did before
 * any of this existed. An application that refuses to open because somebody
 * would rather not grant administrator is worse than one that tracks changes
 * less quickly.
 */
public final class WindowsUsnElevation {

	/** Whichever volume Windows booted from; any volume answers the question. */
	private static final String SYSTEM_DRIVE_VARIABLE = "SystemDrive";
	private static final String DEFAULT_SYSTEM_DRIVE = "C:";

	private static final int GENERIC_READ = 0x80000000;
	private static final int FILE_SHARE_ALL = 0x00000001 | 0x00000002 | 0x00000004;
	private static final int OPEN_EXISTING = 3;

	/** Long enough for the prompt to be answered, short enough to not hang. */
	private static final long PROMPT_TIMEOUT_MINUTES = 5;

	private WindowsUsnElevation() {
	}

	/**
	 * @return true when a restarted, elevated process was started and this one
	 * should end without booting
	 */
	public static boolean relaunchIfNeeded(String[] arguments) {
		String launcher = System.getProperty("jpackage.app-path");

		boolean enabled = UsnElevation.enabled(System.getProperty(UsnElevation.ENABLED_PROPERTY));

		if (!UsnElevation.shouldRelaunch(enabled, System.getProperty("os.name"), launcher,
				UsnElevation.attempted(arguments), canReadSystemVolume())) {
			return false;
		}

		return relaunch(launcher, arguments);
	}

	/**
	 * Opens a volume handle and closes it. The same call the journal reader makes,
	 * so what it answers is exactly the capability at stake - not a guess from the
	 * process token, which can be elevated and still be refused by a filesystem
	 * that has no journal to give.
	 */
	private static boolean canReadSystemVolume() {
		String drive = System.getenv(SYSTEM_DRIVE_VARIABLE);

		String volume = "\\\\.\\" + (drive == null || drive.isBlank() ? DEFAULT_SYSTEM_DRIVE : drive.trim());

		try (Arena arena = Arena.ofConfined()) {
			MemorySegment capture = WindowsKernel32.captureState(arena);

			MemorySegment handle = WindowsKernel32.createFile(WindowsKernel32.wideString(arena, volume), GENERIC_READ,
					FILE_SHARE_ALL, OPEN_EXISTING, 0, capture);

			if (WindowsKernel32.isInvalidHandle(handle)) {
				return false;
			}

			WindowsKernel32.closeHandle(handle, capture);

			return true;
		} catch (RuntimeException _) {
			// Anything unexpected here means the journal is out of reach anyway, and a
			// failure to answer a question must not stop the application from opening.
			return false;
		}
	}

	/**
	 * Through PowerShell rather than a shell32 downcall: a {@code Start-Process}
	 * with {@code -Verb RunAs} is the one documented way to raise a UAC prompt, it
	 * reports a refusal as a non-zero exit, and it costs no new native surface in a
	 * codebase whose FFM glue is deliberately narrow.
	 */
	private static boolean relaunch(String launcher, String[] arguments) {
		try {
			// Whatever this run was given travels with it, plus the marker: the restart
			// has to be the same application, started the same way, not a bare launcher.
			String forwarded = Stream.concat(Arrays.stream(arguments), Stream.of(UsnElevation.ATTEMPTED_ARGUMENT))
					.map(argument -> "'" + argument.replace("'", "''") + "'").collect(Collectors.joining(","));

			List<String> command = List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
					"Start-Process -FilePath '" + launcher.replace("'", "''") + "' -Verb RunAs -ArgumentList "
							+ forwarded);

			// Not inheritIO: this child exists to raise a prompt and end, and its only
			// answer is the exit code. Sharing the console would put PowerShell's output
			// in front of somebody who asked to open an application.
			Process process = new ProcessBuilder(command).redirectOutput(Redirect.DISCARD)
					.redirectError(Redirect.DISCARD).start();

			return process.waitFor(PROMPT_TIMEOUT_MINUTES, TimeUnit.MINUTES) && process.exitValue() == 0;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();

			return false;
		} catch (Exception _) {
			return false;
		}
	}
}