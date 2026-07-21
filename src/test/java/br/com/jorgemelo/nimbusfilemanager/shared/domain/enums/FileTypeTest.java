package br.com.jorgemelo.nimbusfilemanager.shared.domain.enums;

import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.FileCategory;

class FileTypeTest {

	@Test
	void shouldResolveFromMimeType() {
		Assertions.assertThat(FileType.fromMimeType("image/jpeg")).isEqualTo(FileType.PHOTO);
		Assertions.assertThat(FileType.fromMimeType("video/mp4")).isEqualTo(FileType.VIDEO);
		Assertions.assertThat(FileType.fromMimeType("application/pdf")).isEqualTo(FileType.PDF);
		Assertions.assertThat(FileType.fromMimeType(null)).isEqualTo(FileType.OTHER);
	}

	@Test
	void shouldResolveFromExtensionAndPath() {
		Assertions.assertThat(FileType.fromExtension(".JPG")).isEqualTo(FileType.PHOTO);
		Assertions.assertThat(FileType.fromExtension("mp4")).isEqualTo(FileType.VIDEO);
		Assertions.assertThat(FileType.fromPath(Path.of("report.PDF"))).isEqualTo(FileType.PDF);
		Assertions.assertThat(FileType.fromPath(Path.of("README"))).isEqualTo(FileType.OTHER);
	}

	@Test
	void shouldExposeCategoryHelpers() {
		Assertions.assertThat(FileType.PHOTO.isMedia()).isTrue();
		Assertions.assertThat(FileType.PHOTO.isPhoto()).isTrue();
		Assertions.assertThat(FileType.VIDEO.isVideo()).isTrue();
		Assertions.assertThat(FileType.AUDIO.isAudio()).isTrue();
		Assertions.assertThat(FileType.PDF.isDocument()).isTrue();
		Assertions.assertThat(FileType.WORD.isWord()).isTrue();
		Assertions.assertThat(FileType.EXCEL.isExcel()).isTrue();
		Assertions.assertThat(FileType.POWERPOINT.isPowerPoint()).isTrue();
		Assertions.assertThat(FileType.TEXT.isText()).isTrue();
		Assertions.assertThat(FileType.ZIP.isArchive()).isTrue();
		Assertions.assertThat(FileType.OTHER.isOther()).isTrue();
		Assertions.assertThat(FileType.valueOfNullable(null)).isEqualTo(FileType.OTHER);
		Assertions.assertThat(FileType.categoryOf(null)).isEqualTo(FileCategory.OTHER);
		Assertions.assertThat(FileType.displayNameOf(FileType.PHOTO)).isEqualTo("Foto");
		Assertions.assertThat(FileType.folderNameOf(FileType.VIDEO)).isEqualTo("VIDEOS");
	}

	@Test
	void shouldResolveAllSupportedGroupsAndUnknownInputs() {
		Assertions.assertThat(FileType.fromMimeType("audio/mpeg")).isEqualTo(FileType.AUDIO);
		Assertions.assertThat(FileType.fromMimeType("application/vnd.ms-excel")).isEqualTo(FileType.EXCEL);
		Assertions.assertThat(FileType.fromMimeType("application/vnd.ms-powerpoint")).isEqualTo(FileType.POWERPOINT);
		Assertions.assertThat(FileType.fromMimeType("application/json")).isEqualTo(FileType.TEXT);
		Assertions.assertThat(FileType.fromMimeType("application/zip")).isEqualTo(FileType.ZIP);
		Assertions.assertThat(FileType.fromMimeType("application/vnd.rar")).isEqualTo(FileType.RAR);
		Assertions.assertThat(FileType.fromMimeType("application/x-7z-compressed")).isEqualTo(FileType.SEVEN_Z);
		Assertions.assertThat(FileType.fromMimeType(" ")).isEqualTo(FileType.OTHER);
		Assertions.assertThat(FileType.fromMimeType("application/unknown")).isEqualTo(FileType.OTHER);
		Assertions.assertThat(FileType.fromExtension("docx")).isEqualTo(FileType.WORD);
		Assertions.assertThat(FileType.fromExtension("pptx")).isEqualTo(FileType.POWERPOINT);
		Assertions.assertThat(FileType.fromExtension(" ")).isEqualTo(FileType.OTHER);
		Assertions.assertThat(FileType.fromExtension(null)).isEqualTo(FileType.OTHER);
		Assertions.assertThat(FileType.fromPath(null)).isEqualTo(FileType.OTHER);
		// A root path has no file-name element.
		Assertions.assertThat(FileType.fromPath(Path.of("/"))).isEqualTo(FileType.OTHER);
	}

	@Test
	void typePredicatesAreFalseForOtherTypes() {
		Assertions.assertThat(FileType.PHOTO.isAudio()).isFalse();
		Assertions.assertThat(FileType.PHOTO.isPdf()).isFalse();
		Assertions.assertThat(FileType.PHOTO.isWord()).isFalse();
		Assertions.assertThat(FileType.PHOTO.isExcel()).isFalse();
		Assertions.assertThat(FileType.PHOTO.isPowerPoint()).isFalse();
		Assertions.assertThat(FileType.PHOTO.isText()).isFalse();
		Assertions.assertThat(FileType.PDF.isMedia()).isFalse();
		Assertions.assertThat(FileType.PHOTO.isDocument()).isFalse();
		Assertions.assertThat(FileType.PHOTO.isArchive()).isFalse();
		Assertions.assertThat(FileType.PHOTO.isOther()).isFalse();
	}
}