package br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * That an argument sent is an argument received.
 *
 * <p>
 * The elevated restart hands its arguments to PowerShell, which hands them to
 * Windows, which splits them again in the process that starts. Every one of
 * those is a chance to lose a boundary, and one was lost: a list given to
 * {@code Start-Process -ArgumentList} is joined with spaces and quoted by
 * nobody, so the workspace path arrived cut at its first space and the
 * application built a second database under the fragment.
 *
 * <p>
 * Which is why the last test here does not assert the string that was built. It
 * crosses the real boundary - PowerShell, {@code Start-Process}, a JVM of its
 * own - and compares what came out the other side, element by element. Without
 * the UAC prompt, which changes who the process runs as and nothing about how
 * its arguments are parsed.
 */
class WindowsCommandLineTest {

	private static final int GUARD_SECONDS = 120;

	private static final String WORKSPACE = "--nimbus-file-manager.workspace="
			+ "C:\\Users\\jorge\\Nimbus File Manager\\workspace";

	@Test
	void leavesAnOrdinaryArgumentAlone() {
		assertThat(WindowsCommandLine.of(List.of("--usn-elevation-attempted"))).isEqualTo("--usn-elevation-attempted");
	}

	@Test
	void quotesWhatWouldOtherwiseBecomeTwoArguments() {
		assertThat(WindowsCommandLine.of(List.of("--path=C:\\A B\\c"))).isEqualTo("\"--path=C:\\A B\\c\"");
		assertThat(WindowsCommandLine.of(List.of("--path=a\tb"))).isEqualTo("\"--path=a\tb\"");
		assertThat(WindowsCommandLine.of(List.of(""))).isEqualTo("\"\"");
	}

	/**
	 * A backslash means nothing to the splitter until a quote follows it, and then
	 * it escapes that quote - so a path ending in a separator would escape the
	 * closing quote and swallow the next argument.
	 */
	@Test
	void doublesTheBackslashesThatWouldEscapeAQuote() {
		assertThat(WindowsCommandLine.of(List.of("C:\\dir with space\\"))).isEqualTo("\"C:\\dir with space\\\\\"");
		assertThat(WindowsCommandLine.of(List.of("say \"hi\""))).isEqualTo("\"say \\\"hi\\\"\"");
		assertThat(WindowsCommandLine.of(List.of("a\\\\\"b"))).isEqualTo("\"a\\\\\\\\\\\"b\"");
	}

	@Test
	void joinsSeveralArgumentsWithSpacesBetweenThem() {
		assertThat(WindowsCommandLine.of(List.of("--a", "--b=c d", "--e")))
			.isEqualTo("--a \"--b=c d\" --e");
	}

	/**
	 * The whole crossing, for the values a Windows path really carries. Ampersands,
	 * parentheses and apostrophes are ordinary in a folder name and are exactly
	 * what a shell would take an interest in.
	 */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void everyArgumentSurvivesPowerShellAndTheProcessThatStarts(@TempDir Path directory) throws Exception {
		List<String> sent = List.of(WORKSPACE, "--nimbus-file-manager.workspace=D:\\Fotos & Vídeos (2026)\\jorge's",
				"--quoted=say \"hi\"", "--trailing=C:\\ends with\\", "--accented=D:\\Ação\\Müller",
				"--usn-elevation-attempted");

		assertThat(echoOf(sent, directory)).containsExactlyElementsOf(sent);
	}

	/**
	 * Where this stops being ours, written down as a test rather than as a comment
	 * nobody would run again.
	 *
	 * <p>
	 * A character the machine's native encoding cannot represent does not reach
	 * {@code main} at all: the Java launcher decodes the command line Windows gave
	 * it with {@code sun.jnu.encoding}, and what does not fit becomes a question
	 * mark before any code of ours exists. Asserted here by starting a JVM
	 * directly, with no PowerShell, no {@code Start-Process} and no elevation, so
	 * the loss cannot be read as a regression of the transport above.
	 *
	 * <p>
	 * Skipped where the question does not arise - a machine whose native encoding
	 * is UTF-8 loses nothing. If a JDK ever stops losing it, this fails and the
	 * limit can be lifted with a proof rather than a hope.
	 */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void aCharacterOutsideTheNativeEncodingIsLostByTheJavaLauncherItself(@TempDir Path directory) throws Exception {
		String sent = "--path=D:\\日本";

		Charset nativeEncoding = Charset.forName(System.getProperty("native.encoding"));

		Assumptions.assumeFalse(nativeEncoding.newEncoder().canEncode(sent),
				"this machine's native encoding carries it, so there is no limit to record");

		List<String> received = withoutAnyShell(sent, directory);

		assertThat(received).containsExactly("--path=D:\\??");
	}

	/** A JVM started by a JVM: the shortest path there is, and it still loses it. */
	private List<String> withoutAnyShell(String argument, Path directory) throws Exception {
		Path output = directory.resolve("received.txt");

		Process probe = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
				"-D" + ArgumentEchoProbe.OUTPUT_PROPERTY + "=" + output, "-cp", probeClasses(),
				ArgumentEchoProbe.class.getName(), argument).start();

		assertThat(probe.waitFor(GUARD_SECONDS, TimeUnit.SECONDS)).as("the probe finished").isTrue();

		return Files.readAllLines(output);
	}

	/** Runs the probe the way the elevated restart runs the launcher. */
	private List<String> echoOf(List<String> arguments, Path directory) throws Exception {
		Path output = directory.resolve("received.txt");

		String line = WindowsCommandLine.of(List.of("-D" + ArgumentEchoProbe.OUTPUT_PROPERTY + "=" + output, "-cp",
				probeClasses(), ArgumentEchoProbe.class.getName())) + " " + WindowsCommandLine.of(arguments);

		String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();

		String script = "Start-Process -FilePath '" + java.replace("'", "''")
				+ "' -Wait -WindowStyle Hidden -ArgumentList '" + line.replace("'", "''") + "'";

		Process powerShell = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand",
				WindowsPowerShell.encodedCommand(script)).start();

		assertThat(powerShell.waitFor(GUARD_SECONDS, TimeUnit.SECONDS)).as("PowerShell finished").isTrue();
		assertThat(Files.exists(output)).as("the probe wrote what it was given").isTrue();

		return Files.readAllLines(output);
	}

	/**
	 * The probe's own folder of classes, not this JVM's classpath: the probe needs
	 * nothing but the JDK, and the whole test classpath encoded into a command line
	 * is past what Windows accepts. What the application really sends is a launcher
	 * path and a handful of arguments.
	 */
	private String probeClasses() throws Exception {
		return Path.of(ArgumentEchoProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
	}
}