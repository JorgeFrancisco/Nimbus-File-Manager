package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turning "what exactly was analysed, and under which rules" into one string
 * that changes whenever either of them changes.
 *
 * <p>
 * SHA-256 rather than MD5 because the whole point is that two different
 * analyses must never produce the same value: a collision here is a result
 * silently reused for a set it was not computed from, which is the failure this
 * exists to prevent.
 *
 * <p>
 * The hash is the easy half. What makes it trustworthy is the serialization
 * underneath: a canonical form where two different inputs cannot produce the
 * same bytes. Folder paths contain any separator one might pick, so every
 * variable-length field is written with its <b>length in UTF-8 bytes</b> in
 * front of it - {@code id:length:value} - which is unambiguous no matter what
 * the value contains. Fixed separators alone would not be: a folder holding the
 * separator could be read as two fields.
 *
 * <p>
 * The same form is reproduced in SQL so the application can compute a
 * composition digest before queueing, without loading the candidates.
 * {@code SimilarityDigestContractIntegrationTest} pins the two implementations
 * against each other over Unicode, separators, empty values and ordering,
 * because "they look equivalent" is exactly the assumption that would make the
 * key silently wrong.
 */
final class SimilarityDigest {

	private static final String ALGORITHM = "SHA-256";

	private SimilarityDigest() {
	}

	/**
	 * The digest of a composition: the files that actually entered the algorithm,
	 * each with what decides whether it belongs there.
	 *
	 * <p>
	 * The folder travels with the id because eligibility depends on it - a file
	 * moved into an excluded folder leaves the analysed set with its fingerprint
	 * untouched, and a digest of ids alone would not notice. Order is the caller's
	 * and must be the deterministic one the analysis itself used.
	 *
	 * @param folders the folder of each file, in the same order, {@code null}
	 * treated as empty
	 */
	static String ofComposition(List<UUID> ids, List<String> folders) {
		StringBuilder canonical = new StringBuilder();

		for (int index = 0; index < ids.size(); index++) {
			append(canonical, ids.get(index).toString(), folders.get(index));
		}

		return hash(canonical.toString());
	}

	/**
	 * The digest of both exclusion lists.
	 *
	 * <p>
	 * The lists themselves rather than counts and maxima of their ids. Telling one
	 * user's decision from another's is the whole job here: a removal followed by
	 * an insertion leaves a count where it was, and reasoning about whether the
	 * generated ids happen to move is exactly the kind of argument that turns out
	 * to be wrong once. They are small - a person excludes files by hand - so
	 * hashing what they contain costs nothing and needs no argument at all.
	 *
	 * @param files public ids as text, sorted by the caller
	 * @param folders normalized folder paths, sorted by the caller
	 */
	static String ofExclusions(List<String> files, List<String> folders) {
		StringBuilder canonical = new StringBuilder();

		files.forEach(file -> append(canonical, "file", file));
		folders.forEach(folder -> append(canonical, "folder", folder));

		return hash(canonical.toString());
	}

	/**
	 * The digest of the parameters an analysis ran under. Every value that can
	 * change a group goes in, named, so a release that changes one of them stops
	 * matching results produced by the previous one.
	 *
	 * @param parameters ordered by the caller; the order is part of the canonical
	 * form
	 */
	static String ofParameters(Map<String, String> parameters) {
		StringBuilder canonical = new StringBuilder();

		parameters.forEach((name, value) -> append(canonical, name, value));

		return hash(canonical.toString());
	}

	/**
	 * {@code name:byteLength:value}, which is what makes the concatenation
	 * reversible: whatever the value holds, the length in front says where it
	 * ends.
	 */
	private static void append(StringBuilder canonical, String name, String value) {
		String safe = value == null ? "" : value;

		canonical.append(name).append(':').append(safe.getBytes(StandardCharsets.UTF_8).length).append(':')
				.append(safe);
	}

	private static String hash(String canonical) {
		return HexFormat.of().formatHex(digest().digest(canonical.getBytes(StandardCharsets.UTF_8)));
	}

	private static MessageDigest digest() {
		try {
			return MessageDigest.getInstance(ALGORITHM);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(ALGORITHM + " is required to identify a similarity analysis", exception);
		}
	}
}