package br.com.jorgemelo.nimbusfilemanager.media.domain.enums;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;

/**
 * Coarse media-type groups used by the Duplicados screen's type filter. Each
 * group expands to the concrete {@link FileType}s it covers, so the five
 * checkboxes (photos, videos, audio, documents, others) together span every
 * FileType. Groups are derived from {@link FileCategory} where possible, so they
 * never drift if new FileTypes are added.
 */
public enum MediaTypeFilter {

	PHOTO(type -> type == FileType.PHOTO),
	VIDEO(type -> type == FileType.VIDEO),
	AUDIO(type -> type == FileType.AUDIO),
	DOCS(type -> type.category() == FileCategory.DOCUMENT),
	OTHERS(type -> type.category() == FileCategory.ARCHIVE || type.category() == FileCategory.OTHER);

	private final Predicate<FileType> matches;

	MediaTypeFilter(Predicate<FileType> matches) {
		this.matches = matches;
	}

	public Set<FileType> fileTypes() {
		return Arrays.stream(FileType.values()).filter(matches)
				.collect(Collectors.toCollection(() -> EnumSet.noneOf(FileType.class)));
	}

	/**
	 * Union of {@link FileType}s covered by the given groups. An empty or null
	 * selection widens to every FileType, i.e. no filter.
	 */
	public static Set<FileType> fileTypesOf(Collection<MediaTypeFilter> filters) {
		if (filters == null || filters.isEmpty()) {
			return EnumSet.allOf(FileType.class);
		}

		return filters.stream().flatMap(filter -> filter.fileTypes().stream())
				.collect(Collectors.toCollection(() -> EnumSet.noneOf(FileType.class)));
	}
}