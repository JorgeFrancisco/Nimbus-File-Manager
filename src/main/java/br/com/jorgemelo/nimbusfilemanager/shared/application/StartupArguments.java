package br.com.jorgemelo.nimbusfilemanager.shared.application;

import java.util.Arrays;
import java.util.Optional;

/**
 * Refuses to start on a command line that arrived broken.
 *
 * <p>
 * This application has no positional arguments: everything it is ever given is
 * an option, {@code --name} or {@code --name=value}. So a bare word among the
 * arguments did not come from anybody - it is the tail of a value that was split
 * somewhere it should not have been.
 *
 * <p>
 * It exists because that happened, and silently: the workspace path crossed the
 * elevated restart unquoted, arrived as three arguments, and the application
 * read the first fragment as the whole path and created a second, empty database
 * under it. Nothing failed; there was simply a new catalogue where none was
 * meant to be. Checked before the workspace is resolved, so a broken line costs
 * a message rather than a directory.
 *
 * <p>
 * <b>What this cannot do.</b> It catches the failure that was observed because
 * splitting a path on spaces always leaves fragments behind, and a fragment is
 * never a valid argument. It cannot tell that a whole, well-formed value is the
 * wrong one - a workspace someone genuinely chose to call
 * {@code C:\Users\someone\Nimbus} is indistinguishable from a truncation that
 * left no remainder. Correct quoting is the guarantee; this is the second line.
 */
public final class StartupArguments {

	private static final String OPTION_PREFIX = "--";

	private StartupArguments() {
	}

	/**
	 * @throws IllegalArgumentException naming the argument, so whoever reads the
	 * message can see the fragment and recognise where it was cut
	 */
	public static void requireOptionsOnly(String[] arguments) {
		positionalIn(arguments).ifPresent(argument -> {
			throw new IllegalArgumentException("This application takes only --options, and was given \"" + argument
					+ "\". That is not an argument anybody passes: it is part of a value that was split, so the"
					+ " command line arrived broken and starting on it could work against the wrong workspace.");
		});
	}

	static Optional<String> positionalIn(String[] arguments) {
		return Arrays.stream(arguments).filter(argument -> !argument.isEmpty())
				.filter(argument -> !argument.startsWith(OPTION_PREFIX)).findFirst();
	}
}