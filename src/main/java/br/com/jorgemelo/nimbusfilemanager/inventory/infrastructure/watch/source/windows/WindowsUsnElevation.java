package br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.windows;

import java.lang.ProcessBuilder.Redirect;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn.UsnElevation;
import br.com.jorgemelo.nimbusfilemanager.shared.application.WorkspaceLocation;

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

	/** Inside a single-quoted PowerShell string, only the quote itself is special. */
	private static String powerShellLiteral(String value) {
		return value.replace("'", "''");
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
			//
			// And plus the workspace, which is the one thing that would not survive on its
			// own: Windows gives an elevated process a fresh environment built from the
			// registry, so a workspace chosen by NIMBUS_FILE_MANAGER_WORKSPACE would be
			// lost and the restart would come up against the default one. What is sent is
			// what this process resolved, so both sides land on the same folder by the
			// same order of preference. Only that - copying an environment wholesale into
			// an elevated process is the opposite of knowing what crosses.
			//
			// Any workspace argument this run was given is dropped first, so exactly one
			// is passed on. It carries the same value: an argument already here was
			// promoted to the property before this ran, and the property is what resolve
			// prefers.
			Stream<String> kept = Arrays.stream(arguments)
					.filter(argument -> !WorkspaceLocation.isWorkspaceArgument(argument));
			Stream<String> added = Stream.of(WorkspaceLocation.argumentFor(WorkspaceLocation.resolve()),
					UsnElevation.ATTEMPTED_ARGUMENT);

			// Two layers of escaping, and confusing them is what broke this before. The
			// inner one is Windows quoting of the command line the restarted process will
			// split again; the outer one is the PowerShell literal that carries it here.
			// A list given to -ArgumentList is joined with spaces and quoted by nobody, so
			// the command line is built here and handed over as one string.
			String forwarded = WindowsCommandLine.of(Stream.concat(kept, added).toList());

			String script = "Start-Process -FilePath '" + powerShellLiteral(launcher) + "' -Verb RunAs -ArgumentList '"
					+ powerShellLiteral(forwarded) + "'";

			// Encoded rather than written out, because both plainer ways corrupt it: as an
			// argument of -Command it is one more Windows command line for the JVM to
			// build, and the quotes it carries were rewritten there; through standard
			// input it is decoded with the console's OEM code page, which turned UTF-8
			// into mojibake and had no room at all for a Japanese folder name. Base64 of
			// UTF-16LE has neither problem, and leaves only the two layers this class
			// models: the PowerShell literal, and the command line handed to the launcher.
			List<String> command = List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand",
					WindowsPowerShell.encodedCommand(script));

			// Not inheritIO: this child exists to raise a prompt and end, and its only
			// answer is the exit code. Sharing the console would put PowerShell's output
			// in front of somebody who asked to open an application.
			Process process = new ProcessBuilder(command).redirectOutput(Redirect.DISCARD)
					.redirectError(Redirect.DISCARD).start();

			// This waits for PowerShell, which ends as soon as Start-Process has handed
			// the elevated launcher to Windows - there is no -Wait, so the application it
			// starts outlives it, which is the point. What the exit code answers is
			// whether the prompt was accepted, and the timeout is for how long somebody
			// may leave that prompt on screen.
			return process.waitFor(PROMPT_TIMEOUT_MINUTES, TimeUnit.MINUTES) && process.exitValue() == 0;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();

			return false;
		} catch (Exception _) {
			return false;
		}
	}
}