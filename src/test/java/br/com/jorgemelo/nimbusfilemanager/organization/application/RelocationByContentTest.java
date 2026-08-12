package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MissingFile;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.Scan;
import br.com.jorgemelo.nimbusfilemanager.shared.application.LocationChangeException;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.LocationChange;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationChangeFailure;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.KnownContentBatchRow;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.CatalogLocationWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Recognising a file the catalog lost by the only thing that survives a move
 * somebody else made: what it contains.
 *
 * <p>
 * A reconciliation that ends with one file missing and one file it has never
 * seen is describing a move it did not witness. Saying so requires evidence
 * that admits no second reading, and this is deliberately the most conservative
 * one the product has: exactly one lost file with that digest, exactly one
 * unknown file with it, and nothing else in the catalog holding the same bytes.
 * Anything less and the answer is to leave both alone - a wrong match hands one
 * photograph's history, exclusions and groupings to another.
 */
class RelocationByContentTest {

	private static final Instant NOW = Instant.parse("2026-08-14T06:00:00Z");

	private final CatalogFileLocationRepository catalogFileLocationRepository = mock(
			CatalogFileLocationRepository.class);
	private final CatalogFileRepository catalogFileRepository = mock(CatalogFileRepository.class);
	private final CatalogLocationWriter catalogLocationWriter = mock(CatalogLocationWriter.class);

	private final RelocationByContent relocation = new RelocationByContent(catalogFileLocationRepository,
			catalogFileRepository, catalogLocationWriter, new FileHashService(), Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void oneLostFileAndOneUnknownFileHoldingItsBytesIsAMoveNobodyRecorded(@TempDir Path library) throws IOException {
		Path lost = library.resolve("was-here.jpg");
		Path found = Files.writeString(library.resolve("is-now-here.jpg"), "the very same bytes");

		knows(row(7L, lost, digestOf(found), Files.size(found)));

		Scan repaired = relocation.recover(scan(missing(7L, lost), found));

		ArgumentCaptor<LocationChange> change = ArgumentCaptor.forClass(LocationChange.class);

		verify(catalogLocationWriter).relocate(change.capture());

		Assertions.assertThat(change.getValue().catalogFileId()).isEqualTo(7L);
		Assertions.assertThat(change.getValue().newPath()).isEqualTo(found);
		Assertions.assertThat(change.getValue().provenance().source()).isEqualTo(CatalogEventSources.RECONCILE);
		Assertions.assertThat(change.getValue().provenance().evidence())
				.isEqualTo(CatalogEventEvidence.SOLE_CONTENT_MATCH);

		// Neither side is still a problem: the file was found, and the path is no
		// longer one the catalog has never heard of.
		Assertions.assertThat(repaired.missingFiles()).isEmpty();
		Assertions.assertThat(repaired.physicalOnly()).isEmpty();
		Assertions.assertThat(repaired.response().renamed()).as("the pass reports it as the move it recognised")
				.isEqualTo(1);
	}

	@Test
	void aCandidateOfADifferentSizeIsNotEvenRead(@TempDir Path library) throws IOException {
		Path lost = library.resolve("was-here.jpg");
		Path other = Files.writeString(library.resolve("something-else.jpg"), "different bytes entirely");

		knows(row(7L, lost, "a".repeat(64), 999_999L));

		Assertions.assertThat(relocation.recover(scan(missing(7L, lost), other)).missingFiles()).hasSize(1);

		verify(catalogLocationWriter, never()).relocate(any());
	}

	@Test
	void aCandidateOfTheRightSizeButOtherContentIsNotIt(@TempDir Path library) throws IOException {
		Path lost = library.resolve("was-here.jpg");
		Path other = Files.writeString(library.resolve("same-size.jpg"), "0123456789");

		knows(row(7L, lost, "a".repeat(64), Files.size(other)));

		Assertions.assertThat(relocation.recover(scan(missing(7L, lost), other)).missingFiles()).hasSize(1);

		verify(catalogLocationWriter, never()).relocate(any());
	}

	/**
	 * Two lost files holding the same bytes: whichever one is picked, the other's
	 * history goes to the wrong photograph.
	 */
	@Test
	void twoLostFilesWithTheSameBytesAreNotToldApartByThem(@TempDir Path library) throws IOException {
		Path first = library.resolve("first.jpg");
		Path second = library.resolve("second.jpg");
		Path found = Files.writeString(library.resolve("found.jpg"), "identical");

		String digest = digestOf(found);

		knows(row(7L, first, digest, Files.size(found)), row(8L, second, digest, Files.size(found)));

		Assertions.assertThat(relocation.recover(scan(List.of(missing(7L, first), missing(8L, second)),
				List.of(found))).missingFiles()).hasSize(2);

		verify(catalogLocationWriter, never()).relocate(any());
	}

	@Test
	void twoUnknownFilesHoldingTheLostBytesAreNotToldApartEither(@TempDir Path library) throws IOException {
		Path lost = library.resolve("was-here.jpg");
		Path here = Files.writeString(library.resolve("here.jpg"), "identical");
		Path there = Files.writeString(library.resolve("there.jpg"), "identical");

		knows(row(7L, lost, digestOf(here), Files.size(here)));

		Assertions.assertThat(relocation.recover(scan(List.of(missing(7L, lost)), List.of(here, there)))
				.missingFiles()).hasSize(1);

		verify(catalogLocationWriter, never()).relocate(any());
	}

	/**
	 * Another catalogued file holds the same bytes somewhere else. The match is no
	 * longer sole even though both halves of this scan look unambiguous, and a
	 * copy of a photograph is not the photograph.
	 */
	@Test
	void bytesTheCatalogHoldsMoreThanOnceProveNothingAboutWhichFileThisIs(@TempDir Path library) throws IOException {
		Path lost = library.resolve("was-here.jpg");
		Path found = Files.writeString(library.resolve("found.jpg"), "a copy exists");

		String digest = digestOf(found);

		knows(row(7L, lost, digest, Files.size(found)));

		when(catalogFileRepository.digestsHeldMoreThanOnce(any())).thenReturn(List.of(digest));

		Assertions.assertThat(relocation.recover(scan(missing(7L, lost), found)).missingFiles()).hasSize(1);

		verify(catalogLocationWriter, never()).relocate(any());
	}

	@Test
	void aFileWithoutADigestOnRecordCannotBeRecognisedByOne(@TempDir Path library) throws IOException {
		Path lost = library.resolve("was-here.jpg");
		Path found = Files.writeString(library.resolve("found.jpg"), "content");

		knows(row(7L, lost, null, Files.size(found)));

		Assertions.assertThat(relocation.recover(scan(missing(7L, lost), found)).missingFiles()).hasSize(1);

		verify(catalogLocationWriter, never()).relocate(any());
	}

	@Test
	void aScanWithNothingLostOrNothingUnknownAsksTheCatalogNothing(@TempDir Path library) throws IOException {
		Path found = Files.writeString(library.resolve("found.jpg"), "content");

		Assertions.assertThat(relocation.recover(scan(List.of(), List.of(found))).physicalOnly()).hasSize(1);
		Assertions.assertThat(relocation
				.recover(scan(List.of(missing(7L, library.resolve("lost.jpg"))), List.of())).missingFiles())
				.hasSize(1);

		verify(catalogFileLocationRepository, never()).findKnownContentByPaths(any(), anyString());
	}

	/**
	 * The catalog moved under the walk - the file turned up by another route, or
	 * something filled the destination. The refusal is the invariant working, and
	 * the pass that runs next compares a world that has stopped moving.
	 */
	@Test
	void aRepointTheDoorRefusesLeavesBothSidesForTheNextPass(@TempDir Path library) throws IOException {
		Path lost = library.resolve("was-here.jpg");
		Path found = Files.writeString(library.resolve("found.jpg"), "the very same bytes");

		knows(row(7L, lost, digestOf(found), Files.size(found)));

		when(catalogLocationWriter.relocate(any()))
				.thenThrow(new LocationChangeException(LocationChangeFailure.PATH_OCCUPIED, "taken", null));

		Scan unchanged = relocation.recover(scan(missing(7L, lost), found));

		Assertions.assertThat(unchanged.missingFiles()).hasSize(1);
		Assertions.assertThat(unchanged.physicalOnly()).hasSize(1);
		Assertions.assertThat(unchanged.response().renamed()).as("nothing was repaired, and nothing says it was")
				.isZero();
	}

	private void knows(KnownContentBatchRow... rows) {
		List<KnownContentBatchRow> known = List.of(rows);

		when(catalogFileLocationRepository.findKnownContentByPaths(any(), anyString())).thenReturn(known);
	}

	private KnownContentBatchRow row(long catalogFileId, Path path, String sha256, Long sizeBytes) {
		KnownContentBatchRow row = mock(KnownContentBatchRow.class);

		lenient().when(row.getCatalogFileId()).thenReturn(catalogFileId);
		lenient().when(row.getInputPath()).thenReturn(PathUtils.normalize(path));
		lenient().when(row.getSha256()).thenReturn(sha256);
		lenient().when(row.getSizeBytes()).thenReturn(sizeBytes);

		return row;
	}

	private String digestOf(Path path) {
		return new FileHashService().sha256(path);
	}

	private MissingFile missing(long catalogFileId, Path path) {
		return new MissingFile(catalogFileId, PathUtils.normalize(path));
	}

	private Scan scan(MissingFile missing, Path physicalOnly) {
		return scan(List.of(missing), List.of(physicalOnly));
	}

	private Scan scan(List<MissingFile> missing, List<Path> physicalOnly) {
		List<String> unknown = physicalOnly.stream().map(PathUtils::normalize).toList();

		OrganizationReconcileResponse response = new OrganizationReconcileResponse("library", true, false, 1, 1,
				missing.size(), unknown.size(), List.of(), List.of(), 0, 0, 0, 0);

		return new Scan(response, missing, unknown, List.of());
	}
}