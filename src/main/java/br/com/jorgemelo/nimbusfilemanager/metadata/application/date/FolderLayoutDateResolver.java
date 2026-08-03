package br.com.jorgemelo.nimbusfilemanager.metadata.application.date;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

@Service
public class FolderLayoutDateResolver {

	private static final Set<String> KNOWN_SUBCATEGORIES = Set.of("WHATS", "CAMERA", "DRONE", "GOPRO", "SCREENSHOT",
			"OUTROS");

	private static final Set<String> KNOWN_FILE_TYPES = Set.of("IMAGENS", "VIDEOS", "AUDIOS", "PDFS", "WORD", "EXCEL",
			"POWERPOINT", "TEXTOS", "OUTROS");

	private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");

	private static final Pattern TWO_DIGITS = Pattern.compile("\\d{2}");

	private final Clock clock;

	public FolderLayoutDateResolver(Clock clock) {
		this.clock = clock;
	}

	public LocalDateTime resolve(Path file) {
		if (file == null || file.getParent() == null) {
			return null;
		}

		Path fileTypePath = file.getParent();
		Path subcategoryPath = fileTypePath.getParent();
		Path dayPath = subcategoryPath != null ? subcategoryPath.getParent() : null;
		Path yearMonthPath = dayPath != null ? dayPath.getParent() : null;

		if (subcategoryPath == null || dayPath == null || yearMonthPath == null) {
			return null;
		}

		String fileType = fileName(fileTypePath).toUpperCase(Locale.ROOT);
		String subcategory = fileName(subcategoryPath).toUpperCase(Locale.ROOT);
		String day = fileName(dayPath);
		String yearMonth = fileName(yearMonthPath);

		if (fileType.isBlank() || subcategory.isBlank() || day.isBlank() || yearMonth.isBlank()) {
			return null;
		}

		if (!KNOWN_SUBCATEGORIES.contains(subcategory) || !KNOWN_FILE_TYPES.contains(fileType)) {
			return null;
		}

		if (!SIX_DIGITS.matcher(yearMonth).matches() || !TWO_DIGITS.matcher(day).matches()) {
			return null;
		}

		try {
			int year = Integer.parseInt(yearMonth.substring(0, 4));
			int month = Integer.parseInt(yearMonth.substring(4, 6));
			int dayOfMonth = Integer.parseInt(day);

			LocalDate date = LocalDate.of(year, month, dayOfMonth);

			if (!isReasonable(date)) {
				return null;
			}

			return date.atStartOfDay();
		} catch (Exception _) {
			return null;
		}
	}

	private String fileName(Path path) {
		if (path == null || path.getFileName() == null) {
			return "";
		}

		return path.getFileName().toString();
	}

	private boolean isReasonable(LocalDate date) {
		return CaptureYearRange.isPlausible(date.getYear(), clock);
	}
}