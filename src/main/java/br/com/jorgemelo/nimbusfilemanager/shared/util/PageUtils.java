package br.com.jorgemelo.nimbusfilemanager.shared.util;

import java.util.Collection;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

public final class PageUtils {

	private PageUtils() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	public static Pageable firstPage(int size) {
		return PageRequest.of(0, size);
	}

	static Pageable withoutSort(Pageable pageable) {
		return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
	}

	/**
	 * Drops sort (see {@link #withoutSort(Pageable)}) and clamps the page size to
	 * {@code maxPageSize} - without this, a public /api/** caller could request an
	 * arbitrarily large {@code size} query param and force a single query to load
	 * an absurd number of rows at once.
	 */
	public static Pageable capped(Pageable pageable, int maxPageSize) {
		return PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), maxPageSize));
	}

	/**
	 * Validates a saved page-size preference against the allowed sizes, returning
	 * {@code defaultSize} when it is null, blank, not an integer or not an allowed
	 * value. Centralizes the fallback shared by the paginated screens.
	 */
	public static int validSizeOrDefault(String saved, Collection<Integer> allowedSizes, int defaultSize) {
		if (saved != null && !saved.isBlank()) {
			try {
				int value = Integer.parseInt(saved.trim());

				if (allowedSizes.contains(value)) {
					return value;
				}
			} catch (NumberFormatException _) {
				// fall through to the default
			}
		}

		return defaultSize;
	}
}