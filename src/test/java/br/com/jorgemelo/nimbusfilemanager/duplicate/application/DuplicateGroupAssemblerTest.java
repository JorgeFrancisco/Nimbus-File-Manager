package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateFileResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.GroupParts;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaQualityRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.MediaQuality;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SizeResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;

/**
 * Turning a group of duplicates into what the screen offers: which file is kept,
 * which are offered for deletion, and what the quality columns say about each.
 *
 * <p>
 * The edges are what these are about. A group the policy declines to decide, a
 * file the metadata pass never reached, two rows answering for the same public
 * id - none of them is exotic in a library of a hundred thousand files, and each
 * of them reaches this class as a nullable it has to make a decision about
 * before the screen sees anything.
 */
class DuplicateGroupAssemblerTest {

	private final MediaQualityRepository mediaQualityRepository = mock(MediaQualityRepository.class);

	private final DuplicateGroupAssembler assembler = new DuplicateGroupAssembler(new DuplicateKeepPolicy(),
			mediaQualityRepository);

	/**
	 * Asking about nothing is answered without asking the database. The array
	 * parameter is bound whole, and an empty one would still be a round trip.
	 */
	@Test
	void asksTheDatabaseNothingWhenThereAreNoFilesToAskAbout() {
		Assertions.assertThat(assembler.qualityByPublicId(new UUID[0])).isEmpty();

		verifyNoInteractions(mediaQualityRepository);
	}

	/**
	 * Two quality rows for one public id keep the first rather than failing. A
	 * duplicate key would throw out of a stream collector and take the whole screen
	 * with it, and the two rows describe the same file either way.
	 */
	@Test
	void keepsTheFirstQualityRowWhenOneFileSomehowHasTwo() {
		UUID id = UUID.randomUUID();

		when(mediaQualityRepository.findByCatalogFilePublicIdIn(new UUID[] { id }))
				.thenReturn(List.of(quality(id, 4000), quality(id, 3000)));

		Map<UUID, MediaQuality> byId = assembler.qualityByPublicId(new UUID[] { id });

		Assertions.assertThat(byId).hasSize(1);
		Assertions.assertThat(byId.get(id).width()).isEqualTo(4000);
	}

	/**
	 * A group nobody can decide still keeps something: the first file stands in for
	 * the policy's answer, because offering every file in the group for deletion is
	 * the one outcome that loses data.
	 */
	@Test
	void keepsTheFirstFileWhenThePolicyDecidesNothing() {
		DuplicateFileResponse first = file("a.jpg", 1_000);
		DuplicateFileResponse second = file("b.jpg", 1_000);

		GroupParts parts = assembler.assemble(List.of(first, second), Map.of(), true);

		Assertions.assertThat(parts.keep()).isNotNull();
		Assertions.assertThat(parts.deleteCandidates()).extracting(candidate -> candidate.id())
				.doesNotContain(parts.keep().id());
	}

	/** An empty group has nothing to keep, and says so rather than returning null. */
	@Test
	void refusesToAssembleAGroupWithNoFiles() {
		List<DuplicateFileResponse> none = List.of();
		Map<UUID, MediaQuality> quality = Map.of();

		Assertions.assertThatIllegalStateException().isThrownBy(() -> assembler.assemble(none, quality, true))
				.withMessageContaining("does not contain files");
	}

	/**
	 * A file the metadata pass never reached has no quality row, and the screen
	 * shows it with empty columns rather than not showing it: it is still a
	 * duplicate, and hiding it would leave bytes nobody is offered a way to
	 * recover.
	 */
	@Test
	void showsAFileThatHasNoQualityRowWithEmptyColumns() {
		GroupParts parts = assembler.assemble(List.of(file("a.jpg", 2_000), file("b.jpg", 1_000)), Map.of(), true);

		Assertions.assertThat(parts.keep().width()).isNull();
		Assertions.assertThat(parts.keep().height()).isNull();
		Assertions.assertThat(parts.keep().captureDate()).isNull();
		Assertions.assertThat(parts.keep().dateSource()).isNull();
	}

	private MediaQuality quality(UUID id, int width) {
		return new MediaQuality(id, width, 3000, LocalDateTime.parse("2026-01-01T10:00:00"), true,
				MediaSubcategory.CAMERA, DateSource.EXIF, true);
	}

	private DuplicateFileResponse file(String name, long bytes) {
		return new DuplicateFileResponse(UUID.randomUUID(), name, "jpg", "PHOTO", new SizeResponse(bytes, "1 KB"),
				"D:/fotos/" + name, "D:/fotos", Instant.parse("2026-01-01T10:00:00Z"));
	}
}