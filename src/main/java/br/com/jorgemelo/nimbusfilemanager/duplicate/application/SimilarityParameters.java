package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every effective parameter of one analysis, collected in a fixed order so the
 * same analysis always produces the same digest.
 *
 * <p>
 * "Effective" is the word that matters: what the code will actually use, after
 * clamping and after defaults, not what a configuration file happens to say. A
 * value out of range that normalizes to a bound must produce the digest of the
 * bound, or two analyses that ran identically would look different - and two
 * that ran differently could look the same.
 *
 * <p>
 * The list came from the code rather than from the design document, which named
 * three parameters for the photo path and none of the video ones beyond a
 * mention of "quorum". What is here is what the two algorithms read.
 */
class SimilarityParameters {

	private final Map<String, String> values = new LinkedHashMap<>();

	SimilarityParameters with(String name, Object value) {
		values.put(name, String.valueOf(value));

		return this;
	}

	String digest() {
		return SimilarityDigest.ofParameters(values);
	}
}