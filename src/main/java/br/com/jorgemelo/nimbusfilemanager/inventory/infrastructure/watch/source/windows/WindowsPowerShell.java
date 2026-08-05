package br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.windows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Hands a script to PowerShell without a code page in the way.
 *
 * <p>
 * Windows PowerShell reads a script from standard input using the console's OEM
 * code page - CP850 on a Brazilian machine - so a script written as UTF-8 came
 * back as mojibake, and any character that code page has no room for, such as a
 * Japanese one in a folder name, was lost before the script was ever parsed. As
 * an argument it fares no better: that argument is one more Windows command line
 * for the JVM to build.
 *
 * <p>
 * {@code -EncodedCommand} is PowerShell's own answer, and it settles both at
 * once. The script travels as UTF-16LE, which is what PowerShell is, and the
 * argument that carries it is Base64 - letters, digits and three punctuation
 * marks, with no space and no quote for anything downstream to interpret.
 *
 * <p>
 * This is not a way around quoting. The command line handed to the elevated
 * launcher is still built and quoted by {@link WindowsCommandLine}, and proved
 * across the real boundary; what this fixes is only how the script itself
 * reaches PowerShell.
 */
final class WindowsPowerShell {

	private WindowsPowerShell() {
	}

	/** The value for {@code -EncodedCommand}: the script as PowerShell reads it. */
	static String encodedCommand(String script) {
		return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
	}
}