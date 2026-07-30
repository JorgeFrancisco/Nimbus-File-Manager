package br.com.jorgemelo.nimbusfilemanager.shared.application;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * Single localization point for where a capture date came from, shared by the
 * duplicates screen and the metadata rebuild simulation - the browser never
 * turns a source code into a word. The switch uses literal bundle keys (not a
 * computed prefix + enum name) so every key stays visible to the i18n
 * key-parity test.
 */
@Component
public class DateSourceLabels extends LocalizedComponent {

	/** The label of a possibly absent source, ready to render. */
	public String label(DateSource source) {
		if (source == null) {
			return "—";
		}

		return switch (source) {
		case EXIF -> "EXIF";
		case MEDIA_INFO -> message("backend.dateSource.media");
		case FILE_NAME_CONFIRMED -> message("backend.dateSource.nameConfirmed");
		case FILE_NAME -> message("backend.dateSource.name");
		case FOLDER_LAYOUT -> message("backend.dateSource.folder");
		case FILE_MODIFIED_AT -> message("backend.dateSource.file");
		case FILE_CREATED_AT -> message("backend.dateSource.created");
		case UNKNOWN -> message("backend.dateSource.unknown");
		};
	}
}