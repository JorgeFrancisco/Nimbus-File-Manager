package br.com.jorgemelo.nimbusfilemanager.shared.domain.enums;

public enum MediaSubcategory {

	CAMERA("CAMERA"), CELLPHONE("CAMERA"), WHATSAPP("WHATS"), AIRBRUSH("AIRBRUSH"), PHOTOGRID("PHOTOGRID"),
	DRONE("DRONE"), GOPRO("GOPRO"), SCREENSHOT("SCREENSHOT"), UNKNOWN("OUTROS"), OTHER("OUTROS");

	private final String folderName;

	MediaSubcategory(String folderName) {
		this.folderName = folderName;
	}

	public String folderName() {
		return folderName;
	}

	public static String folderNameOf(MediaSubcategory subcategory) {
		return subcategory == null ? OTHER.folderName() : subcategory.folderName();
	}
}