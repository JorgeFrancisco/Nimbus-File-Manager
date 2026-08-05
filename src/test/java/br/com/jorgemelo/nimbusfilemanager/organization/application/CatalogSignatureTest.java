package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.Month;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.CatalogSignatureProjection;

/**
 * The signature is what turns "the plan you are looking at is stale" from
 * something the user finds out afterwards into something the screen says.
 *
 * <p>
 * What has to hold is that it moves when the library moves and stands still when
 * it does not - and that asking about a folder nobody catalogued is not the same
 * as asking about one that changed.
 */
class CatalogSignatureTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.MAY, 1, 10, 0);

	private final CatalogFileRepository catalogFileRepository = mock(CatalogFileRepository.class);

	private final CatalogSignature signature = new CatalogSignature(catalogFileRepository);

	@Test
	void theSameCatalogGivesTheSameSignature() {
		when(catalogFileRepository.signatureUnder(anyString(), anyString())).thenReturn(projection(120, NOW));

		Assertions.assertThat(signature.of("C:/input")).isEqualTo(signature.of("C:/input"));
	}

	@Test
	void aFileArrivingOrLeavingMovesTheSignature() {
		when(catalogFileRepository.signatureUnder(anyString(), anyString())).thenReturn(projection(120, NOW),
				projection(121, NOW));

		Assertions.assertThat(signature.of("C:/input")).isNotEqualTo(signature.of("C:/input"));
	}

	/**
	 * A move leaves the count alone - it is the same files - so the timestamp is
	 * what catches it. That is why the query reads the location's, not the file's:
	 * moving a file is exactly what rewrites its location row.
	 */
	@Test
	void aFileMovingWithoutTheCountChangingStillMovesTheSignature() {
		when(catalogFileRepository.signatureUnder(anyString(), anyString())).thenReturn(projection(120, NOW),
				projection(120, NOW.plusMinutes(1)));

		Assertions.assertThat(signature.of("C:/input")).isNotEqualTo(signature.of("C:/input"));
	}

	@Test
	void anEmptyFolderStillHasASignature() {
		when(catalogFileRepository.signatureUnder(anyString(), anyString())).thenReturn(projection(0, null));

		Assertions.assertThat(signature.of("C:/input")).isEqualTo("0:-");
	}

	/**
	 * No folder means no question to answer. Returning a signature would let a plan
	 * compare against one, and comparing against nothing is how a warning becomes
	 * permanent noise.
	 */
	@Test
	void aFolderThatIsNotWorthAskingAboutHasNoSignature() {
		Assertions.assertThat(signature.of(null)).isNull();
		Assertions.assertThat(signature.of("  ")).isNull();

		verify(catalogFileRepository, never()).signatureUnder(any(), any());
	}

	@Test
	void aCatalogThatAnswersNothingHasNoSignature() {
		when(catalogFileRepository.signatureUnder(anyString(), anyString())).thenReturn(null);

		Assertions.assertThat(signature.of("C:/input")).isNull();
	}

	/**
	 * The descendant pattern is built by {@code PathUtils} and read with an
	 * explicit escape, because a Windows path is full of backslashes - which are
	 * the escape character of {@code LIKE} - and file names carry {@code _} and
	 * {@code %}, which are its wildcards.
	 */
	@Test
	void theFolderIsAskedAboutByItselfAndByItsDescendants() {
		when(catalogFileRepository.signatureUnder(anyString(), anyString())).thenReturn(projection(1, NOW));

		signature.of("C:/input");

		ArgumentCaptor<String> folder = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);

		verify(catalogFileRepository).signatureUnder(folder.capture(), pattern.capture());

		Assertions.assertThat(pattern.getValue()).startsWith(folder.getValue().replace("\\", "\\\\")).endsWith("%");
	}

	private CatalogSignatureProjection projection(long fileCount, LocalDateTime latestUpdate) {
		return new CatalogSignatureProjection() {

			@Override
			public long getFileCount() {
				return fileCount;
			}

			@Override
			public LocalDateTime getLatestUpdate() {
				return latestUpdate;
			}
		};
	}
}