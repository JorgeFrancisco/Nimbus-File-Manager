package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * User-facing wording for locations that have no place name to show. The text
 * lives in the bundles and is resolved here, so nothing translated is ever
 * persisted and no screen invents its own wording for the same state.
 */
@Component
public class LocationLabels extends LocalizedComponent {

	/** Label for a coordinate that resolved to open water. */
	public String openSea() {
		return message("backend.location.openSea");
	}
}