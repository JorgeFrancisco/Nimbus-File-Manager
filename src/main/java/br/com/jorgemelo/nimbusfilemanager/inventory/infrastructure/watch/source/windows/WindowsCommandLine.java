package br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.windows;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Turns a list of arguments into the single command line Windows expects.
 *
 * <p>
 * Windows hands a process one string, not a list, and every process splits it
 * again with the rules of {@code CommandLineToArgvW}. Anything that builds that
 * string has to quote by those rules, and this is the only place here that does.
 *
 * <p>
 * Why it exists: {@code Start-Process -ArgumentList} given a list joins the
 * elements with spaces and quotes none of them, so an argument carrying a path
 * with spaces arrived at the restarted application as several arguments. The
 * workspace {@code C:\Users\...\Nimbus File Manager\workspace} became
 * {@code C:\Users\...\Nimbus}, and the application started a second, empty
 * database there without a word.
 *
 * <p>
 * Naive quoting is not enough, which is why this is not
 * {@code "\"" + value + "\""}: a backslash means nothing to the splitter until a
 * quote follows it, and then it escapes that quote - so the backslashes running
 * up to a quote, and the ones ending a quoted argument, have to be doubled or
 * the quote they meet is the wrong one. A Windows path ending in a separator is
 * exactly that case.
 */
final class WindowsCommandLine {

	private WindowsCommandLine() {
	}

	/** The arguments as one string, each surviving as one argument again. */
	static String of(List<String> arguments) {
		return arguments.stream().map(WindowsCommandLine::quote).collect(Collectors.joining(" "));
	}

	private static boolean needsQuoting(String argument) {
		return argument.isEmpty() || argument.chars().anyMatch(c -> c == ' ' || c == '\t' || c == '"');
	}

	private static String quote(String argument) {
		if (!needsQuoting(argument)) {
			return argument;
		}

		StringBuilder quoted = new StringBuilder("\"");

		int backslashes = 0;

		for (char character : argument.toCharArray()) {
			if (character == '\\') {
				backslashes++;
			} else if (character == '"') {
				// The run before a quote escapes it, so it has to be doubled first, and
				// then one more backslash is what makes this quote literal.
				quoted.append("\\".repeat(backslashes * 2 + 1)).append('"');

				backslashes = 0;
			} else {
				quoted.append("\\".repeat(backslashes)).append(character);

				backslashes = 0;
			}
		}

		// The closing quote is a quote too: whatever ran up to it escapes it.
		return quoted.append("\\".repeat(backslashes * 2)).append('"').toString();
	}
}