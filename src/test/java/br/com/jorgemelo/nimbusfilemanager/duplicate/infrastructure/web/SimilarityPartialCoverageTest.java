package br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/**
 * What the Duplicados screen is allowed to claim when it only looked at part of
 * the library.
 *
 * <p>
 * The analysis is capped, so a run can examine 8.000 of 119.813 eligible files
 * and find nothing - and the screen used to report that as "no similar photos
 * were found", full stop. Two different statements were being collapsed into
 * one: what is true of the files examined, and what is true of the library.
 * These tests hold the wording to the first.
 */
class SimilarityPartialCoverageTest {

	private static final Path SCREEN = Path.of("src/main/resources/templates/app/duplicates.html");

	/**
	 * The verdict is chosen by coverage, not merely accompanied by a note about
	 * it: a user who reads "nothing found" and stops reading must not be misled by
	 * what they skipped.
	 */
	@Test
	void theEmptyVerdictIsScopedToWhatWasExaminedWheneverCoverageIsIncomplete() throws Exception {
		String screen = Files.readString(SCREEN);

		assertThat(screen).contains("similarityPublished and similarityCoverageComplete and similarityLibraryComplete")
				.contains("(!similarityCoverageComplete or !similarityLibraryComplete)")
				.contains("#{duplicates.empty.similarPartial(${similarityAnalyzed})}")
				.contains("#{duplicates.empty.videosPartial(${similarityAnalyzed})}");
	}

	/**
	 * The second half of the condition, and the one that was missing: an analysis
	 * can have compared everything it was able to compare while the library has no
	 * fingerprint for most of its files. The final verdict needs both, and the
	 * partial wording needs only one of them to be false.
	 */
	@Test
	void theFinalVerdictRequiresTheLibraryToHaveBeenFingerprintedInFull() throws Exception {
		String screen = Files.readString(SCREEN);

		for (String tab : new String[] { "similar", "videos" }) {
			assertThat(screen).as("final verdict on the %s tab", tab)
					.contains("activeTab == '" + tab + "' and similarityPublished and similarityCoverageComplete"
							+ " and similarityLibraryComplete");
		}
	}

	/** The three numbers are all shown, so none of them can be inferred wrongly. */
	@Test
	void thePartialNoticeReportsExaminedEligibleAndTheCap() throws Exception {
		String notice = "#{duplicates.similarity.partial(${similarityAnalyzed}, ${similarityEligible},"
				+ " ${similarityCandidateLimit})}";

		assertThat(Files.readString(SCREEN)).contains(notice);
	}

	/**
	 * The limitation that is easiest to omit and worst to discover alone: pressing
	 * the button again re-examines the same files. Saying so is the difference
	 * between a cap and a dead end the user maps out by themselves.
	 */
	@Test
	void thePartialNoticeSaysThatRunningItAgainDoesNotAdvance() throws Exception {
		assertThat(messages("messages.properties").getProperty("duplicates.similarity.partial"))
				.contains("mesmo conjunto");
		assertThat(messages("messages_en.properties").getProperty("duplicates.similarity.partial"))
				.contains("same set");
	}

	/** Partial wording exists in both languages, for both media. */
	@Test
	void bothLanguagesWordThePartialVerdictForPhotosAndVideos() throws Exception {
		for (String bundle : new String[] { "messages.properties", "messages_en.properties" }) {
			Properties properties = messages(bundle);

			assertThat(properties.getProperty("duplicates.empty.similarPartial")).as(bundle).isNotBlank()
					.contains("{0}");
			assertThat(properties.getProperty("duplicates.empty.videosPartial")).as(bundle).isNotBlank()
					.contains("{0}");
		}
	}

	private Properties messages(String bundle) throws Exception {
		Properties properties = new Properties();

		try (InputStream stream = getClass().getClassLoader().getResourceAsStream(bundle)) {
			assertThat(stream).as("bundle %s must exist on the classpath", bundle).isNotNull();

			properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
		}

		return properties;
	}
}