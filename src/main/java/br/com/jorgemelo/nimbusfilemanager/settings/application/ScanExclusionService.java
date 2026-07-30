package br.com.jorgemelo.nimbusfilemanager.settings.application;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.ExtensionRules;
import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.FolderRules;
import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.QuarantineRule;
import br.com.jorgemelo.nimbusfilemanager.shared.util.ExtensionUtils;

@Service
public class ScanExclusionService {

	static final List<String> DEFAULT_EXCLUDED_EXTENSIONS = List.of("tmp", "temp", "ini", "db", "lnk", "part",
			"crdownload");
	static final List<String> DEFAULT_EXCLUDED_FOLDERS = List.of(".git", ".svn", ".hg", "node_modules", "target",
			"build", "out");

	private static final Pattern SPLIT_SEPARATORS = Pattern.compile("[,\\r\\n;]+");

	private final AppSettingService appSettingService;

	// Parsed exclusion rules, memoized by the raw setting string. A reconcile/scan
	// walk calls
	// isExcluded() once per file, and re-splitting the setting (plus recompiling
	// glob patterns)
	// on every path showed up as a hot spot in a CPU profile. The rules are rebuilt
	// only when the
	// setting text changes; a single volatile holder avoids torn reads across scan
	// threads.
	private final AtomicReference<FolderRules> folderRules = new AtomicReference<>(FolderRules.EMPTY);
	private final AtomicReference<ExtensionRules> extensionRules = new AtomicReference<>(ExtensionRules.EMPTY);
	private final AtomicReference<QuarantineRule> quarantineRule = new AtomicReference<>(QuarantineRule.EMPTY);

	public ScanExclusionService(AppSettingService appSettingService) {
		this.appSettingService = appSettingService;
	}

	public List<String> excludedExtensions() {
		return extensionRules().extensions();
	}

	public List<String> excludedFolders() {
		return folderRules().patterns();
	}

	public boolean isExcluded(Path path) {
		return isExcluded(null, path);
	}

	public boolean isExcluded(Path root, Path path) {
		if (path == null) {
			return false;
		}

		return isExcludedExtension(path) || isExcludedFolder(root, path) || isWithinQuarantine(path);
	}

	/**
	 * Absolute, normalized path of the configured quarantine (trash) folder, or
	 * {@code null} when no quarantine is configured. Memoized by the raw setting
	 * string so the path is only rebuilt when the setting changes (same pattern as
	 * the folder/extension rules).
	 */
	public Path quarantineRoot() {
		String raw = setting(SettingsConstants.TRASH_FOLDER, "");

		QuarantineRule current = quarantineRule.get();

		if (!Objects.equals(raw, current.raw())) {
			Path root = raw == null || raw.isBlank() ? null : Path.of(raw).toAbsolutePath().normalize();

			current = new QuarantineRule(raw, root);

			quarantineRule.set(current);
		}

		return current.root();
	}

	/**
	 * Whether {@code path} lives inside (or is) the configured quarantine folder.
	 * Files soft-deleted into quarantine must never be re-inventoried, reconciled
	 * or organized - otherwise they reappear as duplicate candidates on the
	 * Duplicados screen. Returns {@code false} when no quarantine is configured.
	 */
	public boolean isWithinQuarantine(Path path) {
		if (path == null) {
			return false;
		}

		Path root = quarantineRoot();

		return root != null && path.toAbsolutePath().normalize().startsWith(root);
	}

	boolean isExcludedExtension(Path path) {
		String extension = ExtensionUtils.fromPath(path);

		return !extension.isBlank() && excludedExtensions().contains(extension);
	}

	boolean isExcludedFolder(Path root, Path path) {
		List<FolderMatcher> matchers = folderRules().matchers();

		if (matchers.isEmpty() || path == null) {
			return false;
		}

		Path scoped = root != null && path.startsWith(root) ? root.relativize(path) : path;

		for (Path part : scoped) {
			String folderName = part.toString();

			for (FolderMatcher matcher : matchers) {
				if (matcher.matches(folderName)) {
					return true;
				}
			}
		}

		return false;
	}

	List<String> normalizeExtensions(List<String> extensions) {
		if (extensions == null) {
			return List.of();
		}

		return extensions.stream().filter(value -> value != null && !value.isBlank()).map(ExtensionUtils::normalize)
				.distinct().toList();
	}

	public List<String> normalizePatterns(List<String> patterns) {
		if (patterns == null) {
			return List.of();
		}

		return patterns.stream().flatMap(value -> split(value).stream()).map(String::trim)
				.filter(value -> !value.isBlank()).distinct().toList();
	}

	private ExtensionRules extensionRules() {
		String raw = setting(SettingsConstants.SCAN_EXCLUDED_EXTENSIONS, String.join(",", DEFAULT_EXCLUDED_EXTENSIONS));

		ExtensionRules current = extensionRules.get();

		if (!Objects.equals(raw, current.raw())) {
			current = new ExtensionRules(raw, normalizeExtensions(split(raw)));

			extensionRules.set(current);
		}

		return current;
	}

	private FolderRules folderRules() {
		String raw = setting(SettingsConstants.SCAN_EXCLUDED_FOLDERS, String.join(",", DEFAULT_EXCLUDED_FOLDERS));

		FolderRules current = folderRules.get();

		if (!Objects.equals(raw, current.raw())) {
			List<String> patterns = normalizePatterns(split(raw));

			current = new FolderRules(raw, patterns, patterns.stream().map(FolderMatcher::of).toList());

			folderRules.set(current);
		}

		return current;
	}

	private String setting(String key, String fallback) {
		return appSettingService == null ? fallback : appSettingService.stringValue(key, fallback);
	}

	private static List<String> split(String value) {
		if (value == null || value.isBlank()) {
			return List.of();
		}

		return SPLIT_SEPARATORS.splitAsStream(value).toList();
	}
}