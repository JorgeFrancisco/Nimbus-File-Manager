package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The digest that decides whether a published analysis may answer a question.
 *
 * <p>
 * What these tests defend is not the hash - SHA-256 needs no defending - but the
 * serialization under it. A canonical form where two different inputs can
 * produce the same bytes would make the strongest hash in the world certify a
 * result computed from a different set of files.
 */
class SimilarityDigestTest {

	private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");

	@Test
	void theSameCompositionAlwaysProducesTheSameDigest() {
		String once = SimilarityDigest.ofComposition(List.of(FIRST, SECOND), List.of("C:/fotos", "C:/fotos"));

		String again = SimilarityDigest.ofComposition(List.of(FIRST, SECOND), List.of("C:/fotos", "C:/fotos"));

		Assertions.assertThat(once).isEqualTo(again).hasSize(64);
	}

	/**
	 * Swapping one file for another is a different analysis even when the count
	 * holds - which is the case the old count-and-maxima signature could not tell
	 * apart, and the reason this digest exists.
	 */
	@Test
	void swappingOneMemberChangesTheDigest() {
		UUID third = UUID.fromString("00000000-0000-0000-0000-000000000003");

		String before = SimilarityDigest.ofComposition(List.of(FIRST, SECOND), List.of("C:/fotos", "C:/fotos"));

		String after = SimilarityDigest.ofComposition(List.of(FIRST, third), List.of("C:/fotos", "C:/fotos"));

		Assertions.assertThat(after).isNotEqualTo(before);
	}

	/**
	 * A file moved into an excluded folder leaves the analysis with its fingerprint
	 * untouched. Only the folder travelling with the id catches it.
	 */
	@Test
	void movingAMemberToAnotherFolderChangesTheDigest() {
		String before = SimilarityDigest.ofComposition(List.of(FIRST), List.of("C:/fotos"));

		String after = SimilarityDigest.ofComposition(List.of(FIRST), List.of("C:/fotos/2024"));

		Assertions.assertThat(after).isNotEqualTo(before);
	}

	@Test
	void theOrderOfTheMembersIsPartOfTheComposition() {
		String ascending = SimilarityDigest.ofComposition(List.of(FIRST, SECOND), List.of("a", "b"));

		String descending = SimilarityDigest.ofComposition(List.of(SECOND, FIRST), List.of("b", "a"));

		Assertions.assertThat(descending).isNotEqualTo(ascending);
	}

	/**
	 * The case the length prefix exists for. Without it, {@code id:C:/a} and a
	 * folder that happens to contain the separator would concatenate into the same
	 * bytes as a different pair - and Windows paths contain the separator by
	 * definition.
	 */
	@Test
	void aFolderContainingTheFieldSeparatorCannotImpersonateAnotherComposition() {
		String withColonInFolder = SimilarityDigest.ofComposition(List.of(FIRST), List.of("C:/x:5:y"));

		String withPlainFolder = SimilarityDigest.ofComposition(List.of(FIRST), List.of("C:/x"));

		Assertions.assertThat(withColonInFolder).isNotEqualTo(withPlainFolder);
	}

	/** Accents and non-Latin scripts are folder names like any other. */
	@Test
	void unicodeFoldersAreDistinguished() {
		String accented = SimilarityDigest.ofComposition(List.of(FIRST), List.of("C:/férias"));

		String plain = SimilarityDigest.ofComposition(List.of(FIRST), List.of("C:/ferias"));

		String japanese = SimilarityDigest.ofComposition(List.of(FIRST), List.of("C:/写真"));

		Assertions.assertThat(accented).isNotEqualTo(plain).isNotEqualTo(japanese);
	}

	/** A file with no location row is a real state; it must hash, not throw. */
	@Test
	void anAbsentFolderHashesAsEmpty() {
		String absent = SimilarityDigest.ofComposition(List.of(FIRST), java.util.Collections.singletonList(null));

		String empty = SimilarityDigest.ofComposition(List.of(FIRST), List.of(""));

		Assertions.assertThat(absent).isEqualTo(empty);
	}

	@Test
	void anEmptyCompositionIsStillDeterministic() {
		Assertions.assertThat(SimilarityDigest.ofComposition(List.of(), List.of()))
				.isEqualTo(SimilarityDigest.ofComposition(List.of(), List.of()));
	}

	/**
	 * Two exclusion lists differing only by an item are different definitions, and
	 * the same list in the same order is the same definition.
	 */
	@Test
	void exclusionsAreIdentifiedByWhatTheyContain() {
		String none = SimilarityDigest.ofExclusions(List.of(), List.of());

		String oneFile = SimilarityDigest.ofExclusions(List.of(FIRST.toString()), List.of());

		String oneFolder = SimilarityDigest.ofExclusions(List.of(), List.of("C:/privado"));

		Assertions.assertThat(none).isNotEqualTo(oneFile).isNotEqualTo(oneFolder);
		Assertions.assertThat(oneFile).isNotEqualTo(oneFolder);
		Assertions.assertThat(SimilarityDigest.ofExclusions(List.of(FIRST.toString()), List.of())).isEqualTo(oneFile);
	}

	/**
	 * A parameter that changes is a different analysis. The threshold is the one
	 * users change most, and 95 and 90 must never share a result.
	 */
	@Test
	void everyParameterParticipatesInTheDefinition() {
		String at95 = SimilarityDigest.ofParameters(Map.of("minSimilarity", "95", "candidateLimit", "8000"));

		String at90 = SimilarityDigest.ofParameters(Map.of("minSimilarity", "90", "candidateLimit", "8000"));

		String widerCap = SimilarityDigest.ofParameters(Map.of("minSimilarity", "95", "candidateLimit", "9000"));

		Assertions.assertThat(at95).isNotEqualTo(at90).isNotEqualTo(widerCap);
	}
}