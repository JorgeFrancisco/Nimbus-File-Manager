package br.com.jorgemelo.nimbusfilemanager.settings.application;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.FolderBrowserEntry;
import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.FolderBrowserView;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

@Service
public class FolderBrowserService extends LocalizedComponent {

	private static final int MAX_DIRECTORIES = 1_000;

	public FolderBrowserView browse(String requestedPath) {
		if (requestedPath == null || requestedPath.isBlank()) {
			List<FolderBrowserEntry> roots = Arrays.stream(File.listRoots()).map(File::toPath).map(this::rootEntry)
					.toList();

			return new FolderBrowserView(null, null, roots, false);
		}

		Path current = Path.of(requestedPath).toAbsolutePath().normalize();

		if (!Files.isDirectory(current)) {
			throw new IllegalArgumentException(message("backend.folder.invalid"));
		}

		try (var paths = Files.list(current)) {
			List<FolderBrowserEntry> directories = paths.filter(Files::isDirectory).filter(this::isVisibleFolder)
					.sorted(Comparator.comparing(path -> fileName(path).toLowerCase(Locale.ROOT)))
					.limit(MAX_DIRECTORIES + 1L).map(this::entry).toList();

			boolean truncated = directories.size() > MAX_DIRECTORIES;

			if (truncated) {
				directories = directories.subList(0, MAX_DIRECTORIES);
			}

			Path parent = current.getParent();

			return new FolderBrowserView(current.toString(), parent == null ? null : parent.toString(), directories,
					truncated);
		} catch (IOException | SecurityException exception) {
			throw new IllegalArgumentException(message("backend.folder.listError"), exception);
		}
	}

	private FolderBrowserEntry entry(Path path) {
		return new FolderBrowserEntry(fileName(path), path.toAbsolutePath().normalize().toString());
	}

	/**
	 * A drive root shown with its volume label so the user recognizes it, e.g. "G:\
	 * (Google Drive)" instead of a bare "G:\".
	 */
	private FolderBrowserEntry rootEntry(Path path) {
		String root = path.toString();

		return new FolderBrowserEntry(formatRootName(root, driveLabel(path)), root);
	}

	private String driveLabel(Path path) {
		try {
			return Files.getFileStore(path).name();
		} catch (IOException | RuntimeException _) {
			return null;
		}
	}

	/**
	 * Appends the volume label to a drive root, unless it is empty or unhelpful.
	 */
	static String formatRootName(String root, String label) {
		if (label == null || label.isBlank()) {
			return root;
		}

		String trimmed = label.trim();
		String rootLetters = root.replace("\\", "").replace("/", "");

		// Skip labels that add nothing (equal to the root) or are raw device paths
		// (e.g. "\\?\Volume{guid}") that would only confuse.
		if (trimmed.equalsIgnoreCase(root) || trimmed.equalsIgnoreCase(rootLetters) || trimmed.startsWith("\\")
				|| trimmed.length() > 40) {
			return root;
		}

		return root + " (" + trimmed + ")";
	}

	/**
	 * Hides hidden/system folders (.dotfolders, $RECYCLE.BIN, System Volume
	 * Information, ...).
	 */
	private boolean isVisibleFolder(Path path) {
		String name = fileName(path);

		if (name.startsWith(".") || name.startsWith("$")) {
			return false;
		}

		try {
			return !Files.isHidden(path);
		} catch (IOException _) {
			return true;
		}
	}

	private String fileName(Path path) {
		Path name = path.getFileName();

		return name == null ? path.toString() : name.toString();
	}
}