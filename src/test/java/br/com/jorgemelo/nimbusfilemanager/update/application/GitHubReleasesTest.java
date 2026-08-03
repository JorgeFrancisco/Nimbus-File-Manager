package br.com.jorgemelo.nimbusfilemanager.update.application;

import java.util.stream.Stream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.update.application.dto.PublishedRelease;

/**
 * The document arrives from a server nobody here controls, so every shape it
 * can take has to end somewhere defined. The dangerous direction is the
 * permissive one: a release read as complete when its installer was never
 * attached would send an installation to a download that does not exist.
 */
class GitHubReleasesTest {

	private static final String INSTALLER = "https://example.invalid/Nimbus.File.Manager-6.1.0.msi";

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void readsTheTagAndBothFilesOfACompleteRelease() {
		PublishedRelease release = GitHubReleases.parse(complete(), objectMapper).orElseThrow();

		Assertions.assertThat(release.tag()).isEqualTo("v6.1.0.160");
		Assertions.assertThat(release.installerName()).isEqualTo("Nimbus.File.Manager-6.1.0.msi");
		Assertions.assertThat(release.installerUrl()).isEqualTo(INSTALLER);
		Assertions.assertThat(release.checksumUrl()).isEqualTo(INSTALLER + ".sha256");
		Assertions.assertThat(release.size()).isEqualTo(121930296L);
		Assertions.assertThat(release.page()).isEqualTo("https://example.invalid/releases/tag/v6.1.0.160");
	}

	/**
	 * The checksum is named after the installer, so a naive suffix match could
	 * return it as the installer and hand a 97-byte text file to the installer.
	 */
	@Test
	void doesNotMistakeTheChecksumForTheInstaller() {
		PublishedRelease release = GitHubReleases.parse(complete(), objectMapper).orElseThrow();

		Assertions.assertThat(release.installerUrl()).doesNotEndWith(".sha256");
		Assertions.assertThat(release.checksumUrl()).endsWith(".sha256");
	}

	/**
	 * Every shape that must not be offered. Three are incomplete - an installer
	 * that was never attached, a checksum that was not, no tag at all - and the
	 * fourth is complete but a draft: visible only to whoever created it, and
	 * still free to be deleted or changed, so offering it would point
	 * installations at a release that may not exist tomorrow.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("releasesNotWorthOffering")
	void refusesAReleaseThatIsNotWorthOffering(String shape, String document) {
		Assertions.assertThat(GitHubReleases.parse(document, objectMapper)).as(shape).isEmpty();
	}

	private static Stream<Arguments> releasesNotWorthOffering() {
		return Stream.of(Arguments.of("installer never attached", """
				{"tag_name":"v6.1.0.160","assets":[
				  {"name":"Nimbus.File.Manager-6.1.0.msi.sha256","browser_download_url":"x","size":97}]}"""),
				Arguments.of("no checksum beside the installer", """
						{"tag_name":"v6.1.0.160","assets":[
						  {"name":"Nimbus.File.Manager-6.1.0.msi","browser_download_url":"x","size":1}]}"""),
				Arguments.of("draft", """
						{"tag_name":"v6.1.0.160","draft":true,"assets":[
						  {"name":"a.msi","browser_download_url":"x","size":1},
						  {"name":"a.msi.sha256","browser_download_url":"y","size":97}]}"""),
				Arguments.of("no tag", """
						{"assets":[{"name":"a.msi","browser_download_url":"x","size":1},
						  {"name":"a.msi.sha256","browser_download_url":"y","size":97}]}"""));
	}

	/**
	 * What a rate limit, an error page or a repository with no releases answers
	 * with. None of them is the document, and none may raise.
	 */
	@Test
	void refusesWhatIsNotTheDocument() {
		Assertions.assertThat(GitHubReleases.parse(null, objectMapper)).isEmpty();
		Assertions.assertThat(GitHubReleases.parse("", objectMapper)).isEmpty();
		Assertions.assertThat(GitHubReleases.parse("   ", objectMapper)).isEmpty();
		Assertions.assertThat(GitHubReleases.parse("<html>rate limited</html>", objectMapper)).isEmpty();
		Assertions.assertThat(GitHubReleases.parse("[]", objectMapper)).isEmpty();
		Assertions.assertThat(GitHubReleases.parse("{\"message\":\"Not Found\"}", objectMapper)).isEmpty();
	}

	private static String complete() {
		return """
				{
				  "tag_name": "v6.1.0.160",
				  "draft": false,
				  "html_url": "https://example.invalid/releases/tag/v6.1.0.160",
				  "assets": [
				    {
				      "name": "Nimbus.File.Manager-6.1.0.msi",
				      "browser_download_url": "https://example.invalid/Nimbus.File.Manager-6.1.0.msi",
				      "size": 121930296
				    },
				    {
				      "name": "Nimbus.File.Manager-6.1.0.msi.sha256",
				      "browser_download_url": "https://example.invalid/Nimbus.File.Manager-6.1.0.msi.sha256",
				      "size": 97
				    }
				  ]
				}""";
	}
}