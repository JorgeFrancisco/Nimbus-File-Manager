package br.com.jorgemelo.nimbusfilemanager.shared.domain.repository;

import br.com.jorgemelo.nimbusfilemanager.media.domain.repository.projection.MediaSearchFilter;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.CameraFilter;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.MediaScaleFilter;

/**
 * Small mutable builder so each test can set only the field under test and
 * leave the rest {@code null} (disabled predicate).
 */
public final class FilterBuilder {

	private FileType fileType;
	private String codec;
	private String folder;
	private String extension;
	private Integer year;
	private Integer month;
	private Long minSizeBytes;
	private Long maxSizeBytes;

	public FilterBuilder fileType(FileType value) {
		this.fileType = value;
		return this;
	}

	public FilterBuilder codec(String value) {
		this.codec = value;
		return this;
	}

	public FilterBuilder folder(String value) {
		this.folder = value;
		return this;
	}

	public FilterBuilder extension(String value) {
		this.extension = value;
		return this;
	}

	public FilterBuilder year(Integer value) {
		this.year = value;
		return this;
	}

	public FilterBuilder month(Integer value) {
		this.month = value;
		return this;
	}

	public FilterBuilder minSizeBytes(Long value) {
		this.minSizeBytes = value;
		return this;
	}

	public FilterBuilder maxSizeBytes(Long value) {
		this.maxSizeBytes = value;
		return this;
	}

	public MediaSearchFilter build() {
		return new MediaSearchFilter(fileType, codec, folder, extension, year, month,
				new MediaScaleFilter(minSizeBytes, maxSizeBytes, null, null, null), CameraFilter.ANY, null);
	}
}