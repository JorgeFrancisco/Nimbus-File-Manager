package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.rdcw;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves the root-relative paths from {@code ReadDirectoryChangesW} to
 * absolute paths under the monitored root, de-duplicating within a batch. Pure
 * and native-free (unit-tested on any platform). Back-slash separators from
 * Win32 are accepted; a resolved path that escapes the root (defensive, from a
 * malformed {@code ..}) is dropped.
 */
public class RdcwChangeInterpreter {

	private final Path root;

	RdcwChangeInterpreter(Path root) {
		this.root = root.toAbsolutePath().normalize();
	}

	public List<Path> interpret(List<String> relativePaths) {
		Set<Path> changed = new LinkedHashSet<>();

		for (String relative : relativePaths) {
			Path resolved = root.resolve(relative.replace('\\', '/')).normalize();

			if (resolved.startsWith(root)) {
				changed.add(resolved);
			}
		}

		return new ArrayList<>(changed);
	}
}