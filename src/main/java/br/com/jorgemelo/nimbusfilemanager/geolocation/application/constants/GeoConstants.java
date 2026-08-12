package br.com.jorgemelo.nimbusfilemanager.geolocation.application.constants;

/**
 * What the settings panel words when it says which part of the dataset an update
 * is on.
 *
 * <p>
 * Keys rather than text, because the panel resolves them in the language of
 * whoever is looking. They are the panel's own vocabulary and not the
 * execution's: the row says what happened in {@link GeoMessages} codes, and this
 * is the shorter noun the sentence "Downloading ..." needs.
 */
public final class GeoConstants {

	public static final String STEP_COUNTRY = "settings.geo.step.country";
	public static final String STEP_STATE = "settings.geo.step.state";
	public static final String STEP_MUNICIPALITY = "settings.geo.step.municipality";

	/**
	 * For the stages that are not about one level - completing territories,
	 * publishing, finishing - and for a run that has not said anything yet. Without
	 * it the sentence trailed off after the verb.
	 */
	public static final String STEP_DATASET = "settings.geo.step.dataset";

	private GeoConstants() {
	}
}