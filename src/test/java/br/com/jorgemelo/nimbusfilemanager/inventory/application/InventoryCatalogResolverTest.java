package br.com.jorgemelo.nimbusfilemanager.inventory.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.CatalogPathMatch;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.mapper.CatalogFileMapper;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.model.MetadataResult;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Which catalogued file, if any, a path a scan just walked over is about.
 *
 * <p>
 * The distinction that costs the most to get wrong is between a file that is
 * <em>present</em> at a path and one the catalog merely <em>remembers</em>
 * there. Only the first is a cache hit a scan may skip without opening
 * anything; treating the second as one is what made an inventory load every
 * entity of every batch to find the few that needed reviving.
 */
class InventoryCatalogResolverTest {

	private static final Path FILE = Path.of("D:", "library", "photo.jpg");

	private final CatalogPathMatcher catalogPathMatcher = mock(CatalogPathMatcher.class);
	private final CatalogFileRepository catalogFileRepository = mock(CatalogFileRepository.class);
	private final CatalogFileMapper catalogFileMapper = mock(CatalogFileMapper.class);

	private final InventoryCatalogResolver resolver = new InventoryCatalogResolver(catalogPathMatcher,
			catalogFileRepository, catalogFileMapper);

	@Test
	void aPathTheCatalogHasAnOpinionAboutIsOneItKnows() {
		matches(present(FILE, 7L));

		Assertions.assertThat(resolver.knows(FILE)).isTrue();
	}

	@Test
	void aPathNothingIsSaidAboutIsNotKnown() {
		matches(Map.of());

		Assertions.assertThat(resolver.knows(FILE)).isFalse();
	}

	/**
	 * A file the catalog remembers at a path it has since left is not something a
	 * scan may skip: it has to be looked at, because meeting it again is what
	 * brings it back.
	 */
	@Test
	void onlyAPathAFileIsActuallyAtCountsAsAlreadyDone() {
		Path lost = Path.of("D:", "library", "was-here.jpg");

		matches(Map.of(PathUtils.normalize(FILE), new CatalogPathMatch(PathUtils.normalize(FILE), 7L, List.of()),
				PathUtils.normalize(lost),
				new CatalogPathMatch(PathUtils.normalize(lost), null, List.of(8L))));

		Assertions.assertThat(resolver.present(List.of(FILE, lost))).containsExactly(PathUtils.normalize(FILE));
	}

	@Test
	void aFileTheCatalogIsMeetingForTheFirstTimeIsBuiltFromWhatWasRead() {
		CatalogFile built = CatalogFiles.at(FILE);

		when(catalogFileMapper.toEntity(any(), any())).thenReturn(built);

		Assertions.assertThat(resolver.catalogue(null, FILE, MetadataResult.builder().build()))
				.extracting("entity", "reactivated", "created").containsExactly(built, false, true);
	}

	@Test
	void aFileTheCatalogAlreadyHasIsUpdatedInPlace() {
		CatalogFile existing = CatalogFiles.at(7L, FILE);

		when(catalogFileMapper.updateEntity(any(), any(), any())).thenReturn(true);

		Assertions.assertThat(resolver.catalogue(existing, FILE, MetadataResult.builder().build()))
				.extracting("entity", "reactivated", "created").containsExactly(existing, true, false);

		verify(catalogFileMapper, never()).toEntity(any(), any());
	}

	@Test
	void aPathWithNothingBehindItLoadsNothing() {
		matches(Map.of());

		Assertions.assertThat(resolver.existing(List.of(FILE), false)).isEmpty();

		verify(catalogFileRepository, never()).findAllById(any());
	}

	@Test
	void theEntriesBehindThePathsAreLoadedAndHandedBackByPath() {
		matches(present(FILE, 7L));

		CatalogFile file = CatalogFiles.at(7L, FILE);

		when(catalogFileRepository.findAllById(any())).thenReturn(List.of(file));

		Assertions.assertThat(resolver.existing(List.of(FILE), false))
				.containsExactly(Map.entry(PathUtils.normalize(FILE), file));
	}

	/** With details when the caller says so, which is a different query. */
	@Test
	void aCallerThatNeedsTheWholeFileAsksForItInOneRead() {
		matches(present(FILE, 7L));

		CatalogFile file = CatalogFiles.at(7L, FILE);

		when(catalogFileRepository.findWithDetailsByIdIn(any())).thenReturn(List.of(file));

		Assertions.assertThat(resolver.existing(List.of(FILE), true)).containsValue(file);

		verify(catalogFileRepository, never()).findAllById(any());
	}

	private Map<String, CatalogPathMatch> present(Path path, long catalogFileId) {
		return Map.of(PathUtils.normalize(path),
				new CatalogPathMatch(PathUtils.normalize(path), catalogFileId, List.of()));
	}

	private void matches(Map<String, CatalogPathMatch> matches) {
		when(catalogPathMatcher.match(any())).thenReturn(matches);
	}
}