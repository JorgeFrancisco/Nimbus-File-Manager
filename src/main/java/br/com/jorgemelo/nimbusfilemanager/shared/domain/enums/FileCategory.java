package br.com.jorgemelo.nimbusfilemanager.shared.domain.enums;

public enum FileCategory {

	MEDIA("MIDIAS"), DOCUMENT("DOCUMENTOS"), ARCHIVE("COMPACTADOS"), OTHER("OUTROS");

	private final String folderName;

	FileCategory(String folderName) {
		this.folderName = folderName;
	}

	public String folderName() {
		return folderName;
	}

	public static String folderNameOf(FileCategory category) {
		return category == null ? OTHER.folderName() : category.folderName();
	}

	public boolean isMedia() {
		return this == MEDIA;
	}

	public boolean isDocument() {
		return this == DOCUMENT;
	}

	public boolean isArchive() {
		return this == ARCHIVE;
	}

	public boolean isOther() {
		return this == OTHER;
	}
}