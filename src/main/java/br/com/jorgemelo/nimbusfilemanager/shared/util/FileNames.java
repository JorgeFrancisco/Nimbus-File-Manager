package br.com.jorgemelo.nimbusfilemanager.shared.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * File-name arithmetic shared by every feature that has to put a new file next
 * to an existing one without ever overwriting it (quarantine restore, video
 * conversion). All of it operates on names only - nothing here moves, creates
 * or deletes a file.
 */
public final class FileNames {

	/**
	 * Upper bound on the numbered-name search, to avoid an unbounded loop on a
	 * pathological folder.
	 */
	private static final int MAX_RENAME_ATTEMPTS = 10_000;

	private FileNames() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	/**
	 * {@code foo.jpg} -&gt; {@code foo (1).jpg}, {@code foo (2).jpg}, ... first
	 * name free on disk. Returns {@code desired} itself when nothing occupies it.
	 */
	public static Path nextAvailable(Path desired) {
		if (!Files.exists(desired)) {
			return desired;
		}

		Path folder = desired.getParent();

		String fileName = desired.getFileName().toString();

		String base = baseName(fileName);
		String extension = dottedExtension(fileName);

		for (int index = 1; index <= MAX_RENAME_ATTEMPTS; index++) {
			Path candidate = resolve(folder, base + " (" + index + ")" + extension);

			if (!Files.exists(candidate)) {
				return candidate;
			}
		}

		// Extremely unlikely; fall back to a UUID suffix so the caller can still
		// proceed instead of failing on a folder it cannot name into.
		return resolve(folder, base + " (" + UUID.randomUUID() + ")" + extension);
	}

	/**
	 * Inserts {@code suffix} before the extension: {@code foo.mp4} +
	 * {@code " (H.265)"} -&gt; {@code foo (H.265).mp4}.
	 */
	public static Path withSuffix(Path path, String suffix) {
		String fileName = path.getFileName().toString();

		return resolve(path.getParent(), baseName(fileName) + suffix + dottedExtension(fileName));
	}

	/**
	 * Puts {@code prefix} in front of the name: {@code foo.mp4} + {@code "HEVC - "}
	 * -&gt; {@code HEVC - foo.mp4}.
	 */
	public static Path withPrefix(Path path, String prefix) {
		return resolve(path.getParent(), prefix + path.getFileName());
	}

	/**
	 * Same folder and base name, different extension: {@code foo.avi} +
	 * {@code "mkv"} -&gt; {@code foo.mkv}. A blank extension drops it entirely.
	 */
	public static Path withExtension(Path path, String extension) {
		String normalized = ExtensionUtils.normalize(extension);

		String suffix = normalized.isEmpty() ? "" : "." + normalized;

		return resolve(path.getParent(), baseName(path.getFileName().toString()) + suffix);
	}

	/**
	 * The file name without its extension ({@code foo.tar.gz} -&gt;
	 * {@code foo.tar}).
	 */
	public static String baseName(String fileName) {
		int dot = fileName.lastIndexOf('.');

		return dot > 0 ? fileName.substring(0, dot) : fileName;
	}

	/**
	 * The extension including its dot, or an empty string when there is none. A
	 * leading dot never counts as an extension ({@code .gitignore} has none).
	 */
	private static String dottedExtension(String fileName) {
		int dot = fileName.lastIndexOf('.');

		return dot > 0 ? fileName.substring(dot) : "";
	}

	/**
	 * A file name with no parent (a bare {@code Path.of("foo.mp4")}) resolves
	 * against the current directory, exactly like the JDK does.
	 */
	private static Path resolve(Path folder, String fileName) {
		return folder == null ? Path.of(fileName) : folder.resolve(fileName);
	}
}