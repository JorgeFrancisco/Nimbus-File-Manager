package br.com.jorgemelo.nimbusfilemanager.shared.util;

import java.util.Locale;

/**
 * Icon classification shared by the Arquivos explorer and the Duplicados
 * screen, so both agree on which Bootstrap Icons glyph represents a file type.
 * Preview support lives in {@link FilePreviewSupport}.
 */
public final class FileTypeIcon {

	private FileTypeIcon() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	public static String iconClass(String fileType) {
		return switch (normalize(fileType)) {
		case "PDF" -> "bi-file-earmark-pdf-fill pdf";
		case "WORD", "DOC", "DOCX" -> "bi-file-earmark-word-fill word";
		case "EXCEL", "XLS", "XLSX", "CSV" -> "bi-file-earmark-spreadsheet-fill excel";
		case "POWERPOINT", "PPT", "PPTX" -> "bi-file-earmark-slides-fill powerpoint";
		case "ZIP", "RAR", "SEVEN_Z", "7Z" -> "bi-file-earmark-zip-fill archive";
		case "AUDIO", "MP3", "WAV", "FLAC", "AAC", "OGG", "M4A", "WMA", "OPUS", "AMR" -> "bi-file-earmark-music-fill audio";
		case "VIDEO", "MP4", "MOV", "AVI", "MKV", "WMV", "FLV", "WEBM", "MPEG", "MPG", "3GP", "M4V" -> "bi-file-earmark-play-fill video";
		case "PHOTO", "JPG", "JPEG", "PNG", "GIF", "BMP", "WEBP", "HEIC", "HEIF", "TIF", "TIFF" -> "bi-file-earmark-image-fill image";
		case "TEXT", "TXT", "MD", "LOG", "JSON", "XML", "YAML", "YML" -> "bi-file-earmark-text-fill text";
		default -> "bi-file-earmark-fill generic";
		};
	}

	/**
	 * Stable i18n key for the file type shown as an icon tooltip. Callers resolve
	 * it against the message bundles (templates via {@code #messages.msg}, backend
	 * via {@code message(...)}), so the label text never lives hard-coded in code.
	 */
	public static String iconLabelKey(String fileType) {
		return switch (normalize(fileType)) {
		case "PDF" -> "filetype.pdf";
		case "WORD", "DOC", "DOCX" -> "filetype.word";
		case "EXCEL", "XLS", "XLSX", "CSV" -> "filetype.excel";
		case "POWERPOINT", "PPT", "PPTX" -> "filetype.powerpoint";
		case "ZIP" -> "filetype.zip";
		case "RAR" -> "filetype.rar";
		case "SEVEN_Z", "7Z" -> "filetype.sevenZip";
		case "AUDIO", "MP3", "WAV", "FLAC", "AAC", "OGG", "M4A", "WMA", "OPUS", "AMR" -> "filetype.audio";
		case "VIDEO", "MP4", "MOV", "AVI", "MKV", "WMV", "FLV", "WEBM", "MPEG", "MPG", "3GP", "M4V" -> "filetype.video";
		case "PHOTO", "JPG", "JPEG", "PNG", "GIF", "BMP", "WEBP", "HEIC", "HEIF", "TIF", "TIFF" -> "filetype.image";
		case "TEXT", "TXT", "MD", "LOG", "JSON", "XML", "YAML", "YML" -> "filetype.text";
		default -> "filetype.generic";
		};
	}

	private static String normalize(String fileType) {
		return fileType == null ? "" : fileType.trim().toUpperCase(Locale.ROOT);
	}
}