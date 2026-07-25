package br.com.jorgemelo.nimbusfilemanager.metadata.application.classifier;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;

/**
 * One classification rule tried by {@link MediaSubcategoryRuleEngine}. Mirrors
 * the {@code FileNameDateRule}/{@code FileNameDateRuleEngine} pattern in
 * {@code metadata.filename}, so classification is a list
 * of pluggable rules instead of an if/else chain in
 * {@link MediaSubcategoryResolver}.
 */
public interface MediaSubcategoryRule {

	boolean supports(String fileName, String path);

	MediaSubcategory subcategory();

	/**
	 * Sort key used by {@link MediaSubcategoryRuleEngine} to pick a deterministic
	 * evaluation order (lower first). By convention, prefixed with a zero-padded
	 * number, e.g. {@code
	 * "010_WHATSAPP"}.
	 */
	String name();
}