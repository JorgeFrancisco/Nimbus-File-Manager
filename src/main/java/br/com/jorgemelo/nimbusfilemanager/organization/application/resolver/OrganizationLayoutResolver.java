package br.com.jorgemelo.nimbusfilemanager.organization.application.resolver;

import java.nio.file.Path;
import java.util.List;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;

@Service
public class OrganizationLayoutResolver {

	private static final String YEAR_MONTH_DAY_SUBCATEGORY_FILE_TYPE = "YEAR_MONTH/DAY/SUBCATEGORY/FILE_TYPE";
	private static final String YEAR_MONTH_DAY = "YEAR_MONTH/DAY";
	private static final String YEAR_MONTH_SUBCATEGORY_FILE_TYPE = "YEAR_MONTH/SUBCATEGORY/FILE_TYPE";
	private static final String SUBCATEGORY_YEAR_MONTH_DAY = "SUBCATEGORY/YEAR_MONTH/DAY";
	private static final String FLAT = "FLAT";

	public String normalize(OrganizationLayout layout) {
		if (layout == null) {
			return YEAR_MONTH_DAY_SUBCATEGORY_FILE_TYPE;
		}

		return switch (layout) {
		case DEFAULT, YEAR_MONTH_DAY_SUBCATEGORY_FILE_TYPE -> YEAR_MONTH_DAY_SUBCATEGORY_FILE_TYPE;
		case YEAR_MONTH_DAY -> YEAR_MONTH_DAY;
		case YEAR_MONTH_SUBCATEGORY_FILE_TYPE -> YEAR_MONTH_SUBCATEGORY_FILE_TYPE;
		case SUBCATEGORY_YEAR_MONTH_DAY -> SUBCATEGORY_YEAR_MONTH_DAY;
		case FLAT -> FLAT;
		};
	}

	Path resolveFolder(Path targetPath, String layout, String yearMonth, String day, String subcategory,
			String fileType) {
		return resolveFolder(targetPath, layout, yearMonth, day, subcategory, fileType, List.of());
	}

	/**
	 * Resolves the destination folder, optionally inserting the geographic
	 * subdivision segments (e.g. Brasil/Paraná/Curitiba) right after the date
	 * segments of the layout - the location subdivides the chosen structure, it
	 * never replaces it.
	 */
	Path resolveFolder(Path targetPath, String layout, String yearMonth, String day, String subcategory,
			String fileType, List<String> locationSegments) {
		String normalizedLayout = layout == null || layout.isBlank() ? YEAR_MONTH_DAY_SUBCATEGORY_FILE_TYPE : layout;

		return switch (normalizedLayout) {
		case YEAR_MONTH_DAY_SUBCATEGORY_FILE_TYPE -> appendAll(targetPath.resolve(yearMonth).resolve(day),
				locationSegments).resolve(subcategory).resolve(fileType).normalize();
		case YEAR_MONTH_DAY -> appendAll(targetPath.resolve(yearMonth).resolve(day), locationSegments).normalize();
		case YEAR_MONTH_SUBCATEGORY_FILE_TYPE -> appendAll(targetPath.resolve(yearMonth), locationSegments)
				.resolve(subcategory).resolve(fileType).normalize();
		case SUBCATEGORY_YEAR_MONTH_DAY -> appendAll(targetPath.resolve(subcategory).resolve(yearMonth).resolve(day),
				locationSegments).normalize();
		// Flat: everything goes straight into the target folder, no subfolders
		// (date/category and
		// even location segments are intentionally ignored).
		case FLAT -> targetPath.normalize();
		default -> throw new IllegalArgumentException("Unsupported organization layout: " + layout);
		};
	}

	private Path appendAll(Path folder, List<String> segments) {
		Path result = folder;

		if (segments != null) {
			for (String segment : segments) {
				if (segment != null && !segment.isBlank()) {
					result = result.resolve(segment);
				}
			}
		}

		return result;
	}
}