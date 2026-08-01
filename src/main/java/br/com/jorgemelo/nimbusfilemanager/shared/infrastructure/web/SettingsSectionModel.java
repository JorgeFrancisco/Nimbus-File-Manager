package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web;

import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;

/**
 * One section of the settings screen contributing what it needs to render.
 *
 * <p>
 * The screen gathers sections from several domains - the geographic dataset,
 * the external tools, the catalog backup, the embedded database - and each one
 * used to arrive as its own constructor parameter. That list only grows, and it
 * had already reached the seven-parameter limit; injecting the implementations
 * as a collection means a new section is a new class and nothing else.
 */
public interface SettingsSectionModel {

	/**
	 * @param authentication the signed-in user, which some sections need to read
	 *                       a per-user preference and others ignore
	 */
	void addTo(Model model, Authentication authentication);
}