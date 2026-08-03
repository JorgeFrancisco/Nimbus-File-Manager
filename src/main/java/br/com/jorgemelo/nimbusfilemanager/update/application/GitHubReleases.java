package br.com.jorgemelo.nimbusfilemanager.update.application;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.update.application.dto.PublishedRelease;

/**
 * Reads the release document the API answers with.
 *
 * <p>
 * Kept apart from the call that fetches it so the shapes worth worrying about
 * can be exercised without a network: a release whose installer has not been
 * attached, one with no checksum beside it, a draft, and a body that is not the
 * document at all. Each of those has to end as "nothing to offer" rather than
 * as a half-built release that a later download would fail on.
 *
 * <p>
 * The two files are found by extension rather than by position or by name. The
 * published name carries the version and the packaging replaces its spaces, so
 * matching it literally would break the day either changes - while ".msi" is
 * the one thing about that file which cannot change without this stopping being
 * a Windows installer.
 */
public final class GitHubReleases {

	private static final String INSTALLER_SUFFIX = ".msi";
	private static final String CHECKSUM_SUFFIX = ".msi.sha256";

	private GitHubReleases() {
	}

	public static Optional<PublishedRelease> parse(String document, ObjectMapper mapper) {
		JsonNode root = read(document, mapper);

		if (root == null || root.path("draft").asBoolean()) {
			return Optional.empty();
		}

		String tag = text(root, "tag_name");

		if (tag == null) {
			return Optional.empty();
		}

		JsonNode installer = asset(root, INSTALLER_SUFFIX);
		JsonNode checksum = asset(root, CHECKSUM_SUFFIX);

		if (installer == null || checksum == null) {
			return Optional.empty();
		}

		String installerName = text(installer, "name");
		String installerUrl = text(installer, "browser_download_url");
		String checksumUrl = text(checksum, "browser_download_url");

		if (installerName == null || installerUrl == null || checksumUrl == null) {
			return Optional.empty();
		}

		return Optional.of(new PublishedRelease(tag, text(root, "html_url"), installerName, installerUrl, checksumUrl,
				installer.path("size").asLong()));
	}

	/**
	 * The two suffixes cannot collide: the checksum is named after the installer
	 * plus its own extension, so only one of them ever ends in {@code .msi}.
	 */
	private static JsonNode asset(JsonNode root, String suffix) {
		for (JsonNode asset : root.path("assets")) {
			String name = text(asset, "name");

			if (name != null && name.endsWith(suffix)) {
				return asset;
			}
		}

		return null;
	}

	private static JsonNode read(String document, ObjectMapper mapper) {
		if (document == null || document.isBlank()) {
			return null;
		}

		try {
			JsonNode root = mapper.readTree(document);

			return root == null || !root.isObject() ? null : root;
		} catch (Exception _) {
			return null;
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);

		return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
	}
}