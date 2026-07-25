package br.com.jorgemelo.nimbusfilemanager.media.application.dto;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class FileExplorerEntryTest {

	@Test
	void shouldResolveSpecialStatesAndEveryIconFamily() {
		assertEntry(null, false, true, "bi-question-circle missing", "filetype.missing");
		assertEntry(null, true, false, "bi-folder-fill folder", "filetype.folder");
		assertEntry("pdf", false, false, "bi-file-earmark-pdf-fill pdf", "filetype.pdf");
		assertEntry("docx", false, false, "bi-file-earmark-word-fill word", "filetype.word");
		assertEntry("csv", false, false, "bi-file-earmark-spreadsheet-fill excel", "filetype.excel");
		assertEntry("pptx", false, false, "bi-file-earmark-slides-fill powerpoint", "filetype.powerpoint");
		assertEntry("zip", false, false, "bi-file-earmark-zip-fill archive", "filetype.zip");
		assertEntry("rar", false, false, "bi-file-earmark-zip-fill archive", "filetype.rar");
		assertEntry("7z", false, false, "bi-file-earmark-zip-fill archive", "filetype.sevenZip");
		assertEntry(" mp3 ", false, false, "bi-file-earmark-music-fill audio", "filetype.audio");
		assertEntry("mp4", false, false, "bi-file-earmark-play-fill video", "filetype.video");
		assertEntry("jpeg", false, false, "bi-file-earmark-image-fill image", "filetype.image");
		assertEntry("json", false, false, "bi-file-earmark-text-fill text", "filetype.text");
		assertEntry(null, false, false, "bi-file-earmark-fill generic", "filetype.generic");
	}

	private void assertEntry(String type, boolean directory, boolean missing, String icon, String labelKey) {
		FileExplorerEntry entry = new FileExplorerEntry("name", "path", directory, missing, false, type, false, false,
				false, false, false, null, null, null, null, null, null);

		Assertions.assertThat(entry.iconClass()).isEqualTo(icon);
		Assertions.assertThat(entry.iconLabelKey()).isEqualTo(labelKey);
	}
}