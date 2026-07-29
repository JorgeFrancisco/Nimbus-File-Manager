package br.com.jorgemelo.nimbusfilemanager.shared.domain.enums;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import br.com.jorgemelo.nimbusfilemanager.shared.util.ExtensionUtils;

public enum FileType {

	PHOTO(FileCategory.MEDIA, "IMAGENS", List.of("image/"),
			List.of("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tif", "tiff")),

	VIDEO(FileCategory.MEDIA, "VIDEOS", List.of("video/"),
			List.of("mp4", "mov", "avi", "mkv", "wmv", "flv", "webm", "mpeg", "mpg", "3gp", "m4v")),

	AUDIO(FileCategory.MEDIA, "AUDIOS", List.of("audio/"),
			List.of("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "opus", "amr")),

	PDF(FileCategory.DOCUMENT, "PDFS", List.of("application/pdf"), List.of("pdf")),

	WORD(FileCategory.DOCUMENT, "WORD",
			List.of("application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
			List.of("doc", "docx")),

	EXCEL(FileCategory.DOCUMENT, "EXCEL",
			List.of("application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
			List.of("xls", "xlsx", "csv")),

	POWERPOINT(FileCategory.DOCUMENT, "POWERPOINT",
			List.of("application/vnd.ms-powerpoint",
					"application/vnd.openxmlformats-officedocument.presentationml.presentation"),
			List.of("ppt", "pptx")),

	TEXT(FileCategory.DOCUMENT, "TEXTOS",
			List.of("text/", "application/json", "application/xml", "application/yaml"),
			List.of("txt", "md", "log", "json", "xml", "yaml", "yml")),

	ZIP(FileCategory.ARCHIVE, "ZIP", List.of("application/zip"), List.of("zip")),

	RAR(FileCategory.ARCHIVE, "RAR", List.of("application/vnd.rar", "application/x-rar-compressed"),
			List.of("rar")),

	SEVEN_Z(FileCategory.ARCHIVE, "7Z", List.of("application/x-7z-compressed"), List.of("7z")),

	OTHER(FileCategory.OTHER, "OUTROS", List.of(), List.of());

	private final FileCategory category;
	private final String folderName;
	private final List<String> mimePrefixes;
	private final List<String> extensions;

	FileType(FileCategory category, String folderName, List<String> mimePrefixes, List<String> extensions) {
		this.category = category;
		this.folderName = folderName;
		this.mimePrefixes = mimePrefixes;
		this.extensions = extensions;
	}

	public static FileCategory categoryOf(FileType fileType) {
		return valueOfNullable(fileType).category();
	}

	public FileCategory category() {
		return category;
	}

	public String folderName() {
		return folderName;
	}

	public static String folderNameOf(FileType fileType) {
		return valueOfNullable(fileType).folderName();
	}

	public static FileType valueOfNullable(FileType fileType) {
		return fileType == null ? OTHER : fileType;
	}

	public static FileType fromMimeType(String mimeType) {
		if (mimeType == null || mimeType.isBlank()) {
			return OTHER;
		}

		String value = mimeType.toLowerCase(Locale.ROOT);

		for (FileType fileType : values()) {
			if (fileType.matchesMimeType(value)) {
				return fileType;
			}
		}

		return OTHER;
	}

	public static FileType fromExtension(String extension) {
		if (extension == null || extension.isBlank()) {
			return OTHER;
		}

		String value = ExtensionUtils.normalize(extension);

		for (FileType fileType : values()) {
			if (fileType.extensions.contains(value)) {
				return fileType;
			}
		}

		return OTHER;
	}

	/**
	 * The type of a file: its extension decides, and the mime type only answers
	 * when the extension says nothing. A media mime is refused in that fallback -
	 * an extensionless file sniffed as an image is not treated as a photo, because
	 * the whole media pipeline keys off the extension.
	 *
	 * <p>
	 * Lives here so the one rule serves both the analysis that first types a file
	 * and the rename that re-types it when its extension changes.
	 */
	public static FileType resolve(Path path, String mimeType) {
		FileType byExtension = fromPath(path);

		if (byExtension != OTHER) {
			return byExtension;
		}

		FileType byMime = fromMimeType(mimeType);

		return byMime.isMedia() ? OTHER : byMime;
	}

	public static FileType fromPath(Path path) {
		if (path == null || path.getFileName() == null) {
			return OTHER;
		}

		return fromExtension(ExtensionUtils.fromPath(path));
	}

	private boolean matchesMimeType(String mimeType) {
		return mimePrefixes.stream().anyMatch(mimeType::startsWith);
	}

	public boolean isPhoto() {
		return this == PHOTO;
	}

	public boolean isVideo() {
		return this == VIDEO;
	}

	public boolean isAudio() {
		return this == AUDIO;
	}

	public boolean isPdf() {
		return this == PDF;
	}

	public boolean isWord() {
		return this == WORD;
	}

	public boolean isExcel() {
		return this == EXCEL;
	}

	public boolean isPowerPoint() {
		return this == POWERPOINT;
	}

	public boolean isText() {
		return this == TEXT;
	}

	public boolean isMedia() {
		return category.isMedia();
	}

	public boolean isDocument() {
		return category.isDocument();
	}

	public boolean isArchive() {
		return category.isArchive();
	}

	public boolean isOther() {
		return this == OTHER;
	}
}