package br.com.jorgemelo.nimbusfilemanager.organization.domain.enums;

/**
 * Folder structure organization creates inside the target folder. The label and
 * the description of each layout live in the message bundles, under
 * {@code enum.organizationLayout.*}, because the screen shows them translated;
 * only the example path is data of the layout itself.
 */
public enum OrganizationLayout {

	DEFAULT(""),

	YEAR_MONTH_DAY_SUBCATEGORY_FILE_TYPE("2026-07/10/Fotos/IMAGE"),

	YEAR_MONTH_DAY("2026-07/10"),

	YEAR_MONTH_SUBCATEGORY_FILE_TYPE("2026-07/Fotos/IMAGE"),

	SUBCATEGORY_YEAR_MONTH_DAY("Fotos/2026-07/10"),

	// Kept last on purpose so it is the last option in the dropdown (which iterates
	// the enum order).
	FLAT("direto na pasta de destino");

	private final String example;

	OrganizationLayout(String example) {
		this.example = example;
	}

	public String example() {
		return example;
	}
}