package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.CatalogTimestamp;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentSuspect;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MissingFile;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileIssueResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.projection.MediaLocationReconcileProjection;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Comparing the catalog against the disk, and saying what the two disagree
 * about.
 *
 * <p>
 * This half writes nothing - the pass that acts on what it finds is its own
 * class now, and is tested as one. What is asserted here is the comparison
 * itself: which files the walk sees, which rows it reads, and the three
 * different answers it can reach about a catalogued place.
 */
@ExtendWith(MockitoExtension.class)
class OrganizationReconcileServiceTest {

	@TempDir
	Path tempDir;

	@Mock
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Mock
	private ScanExclusionService scanExclusionService;

	@Test
	void reconcileShouldReportWhatIsOnlyOnDiskAndWhatIsOnlyInTheCatalog() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path ok = Files.writeString(source.resolve("ok.jpg"), "ok");
		Path onlyDisk = Files.writeString(source.resolve("only-disk.jpg"), "disk");
		Path missingDisk = source.resolve("missing.jpg");

		List<MediaLocationReconcileProjection> catalogued = List.of(row(1L, ok), row(2L, missingDisk));

		when(catalogFileLocationRepository.findForReconcile(eq(PathUtils.normalize(source)), any(), eq(0L),
				any(Limit.class))).thenReturn(catalogued);

		var response = service().reconcile(new OrganizationReconcileRequest(source.toString(), true, false, 10));

		Assertions.assertThat(response.filesOnDisk()).isEqualTo(2);
		Assertions.assertThat(response.filesInDatabase()).isEqualTo(2);
		Assertions.assertThat(response.missingOnDisk()).isEqualTo(1);
		Assertions.assertThat(response.missingInDatabase()).isEqualTo(1);
		Assertions.assertThat(response.missingOnDiskSamples().getFirst().catalogFileId()).isEqualTo(2L);
		Assertions.assertThat(response.missingInDatabaseSamples().getFirst().path())
				.isEqualTo(PathUtils.normalize(onlyDisk));
	}

	/**
	 * A file still at the place the catalog has it, holding something else. The
	 * walk was handed its size and its timestamp, so asking costs nothing - and
	 * neither of them proves anything, which is why the answer is a suspicion for
	 * someone else to settle rather than a file counted as gone.
	 */
	@Test
	void aPresentFileWhoseStatMovedIsSuspectedRatherThanCountedMissing() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path edited = Files.writeString(source.resolve("edited.jpg"), "longer than it was");

		List<MediaLocationReconcileProjection> catalogued = List.of(row(4L, edited, 1L, Instant.EPOCH));

		when(catalogFileLocationRepository.findForReconcile(eq(PathUtils.normalize(source)), any(), eq(0L),
				any(Limit.class))).thenReturn(catalogued);

		var scan = service().scan(new OrganizationReconcileRequest(source.toString(), true, false, 10));

		// The place travels with the identifier, and the assertion is on both: a
		// verification is excluded by the path it reads, so a suspect that names none
		// becomes a request the dispatcher can only fail. Asserting the id alone is
		// what let that ship.
		Assertions.assertThat(scan.contentSuspects())
				.containsExactly(new ContentSuspect(4L, PathUtils.normalize(edited)));

		Assertions.assertThat(scan.missingFiles()).isEmpty();
		Assertions.assertThat(scan.response().missingOnDisk()).isZero();
	}

	/**
	 * Many suspects, and still only the pages the walk already reads. The place
	 * comes from the row that revealed the divergence, so no number of suspects
	 * turns into a question per file.
	 */
	@Test
	void everySuspectCarriesItsPlaceWithoutAskingTheDatabaseAgain() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path first = Files.writeString(source.resolve("first.jpg"), "longer than it was");
		Path second = Files.writeString(source.resolve("second.jpg"), "also longer than it was");
		Path third = Files.writeString(source.resolve("third.jpg"), "longer still than it was");

		List<MediaLocationReconcileProjection> catalogued = List.of(row(4L, first, 1L, Instant.EPOCH),
				row(5L, second, 1L, Instant.EPOCH), row(6L, third, 1L, Instant.EPOCH));

		when(catalogFileLocationRepository.findForReconcile(eq(PathUtils.normalize(source)), any(), eq(0L),
				any(Limit.class))).thenReturn(catalogued);

		var scan = service().scan(new OrganizationReconcileRequest(source.toString(), true, false, 10));

		Assertions.assertThat(scan.contentSuspects()).containsExactlyInAnyOrder(
				new ContentSuspect(4L, PathUtils.normalize(first)),
				new ContentSuspect(5L, PathUtils.normalize(second)),
				new ContentSuspect(6L, PathUtils.normalize(third)));

		// Only the keyed pages of the walk: the page that returned the rows and the
		// one that ends it. Nothing per suspect.
		verify(catalogFileLocationRepository, times(2)).findForReconcile(any(), any(), anyLong(), any(Limit.class));
		verifyNoMoreInteractions(catalogFileLocationRepository);
	}

	/** Same place, same size, same timestamp: nothing to say about it at all. */
	@Test
	void aPresentFileThatStillMatchesIsNotSuspectedOfAnything() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path settled = Files.writeString(source.resolve("settled.jpg"), "content");

		List<MediaLocationReconcileProjection> catalogued = List.of(row(5L, settled));

		when(catalogFileLocationRepository.findForReconcile(eq(PathUtils.normalize(source)), any(), eq(0L),
				any(Limit.class))).thenReturn(catalogued);

		var scan = service().scan(new OrganizationReconcileRequest(source.toString(), true, false, 10));

		Assertions.assertThat(scan.contentSuspects()).isEmpty();
		Assertions.assertThat(scan.missingFiles()).isEmpty();
	}

	/**
	 * The samples stop where the screen stops; the work does not. They were once
	 * the same collection, and a pass could report five thousand files gone and
	 * hand a hundred of them to the writer - stating the difference truthfully in
	 * one number and repairing a fiftieth of it.
	 */
	@Test
	void everyDifferenceIsCarriedEvenWhenOnlyOneOfThemIsShown() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path first = source.resolve("a.jpg");
		Path second = source.resolve("b.jpg");

		Files.writeString(source.resolve("stranger-one.jpg"), "one");
		Files.writeString(source.resolve("stranger-two.jpg"), "two");

		List<MediaLocationReconcileProjection> catalogued = List.of(row(1L, first), row(2L, second));

		when(catalogFileLocationRepository.findForReconcile(eq(PathUtils.normalize(source)), any(), eq(0L),
				any(Limit.class))).thenReturn(catalogued);

		var scan = service().scan(new OrganizationReconcileRequest(source.toString(), true, false, 1));

		Assertions.assertThat(scan.response().missingOnDiskSamples()).hasSize(1);
		Assertions.assertThat(scan.response().missingInDatabaseSamples()).hasSize(1);

		Assertions.assertThat(scan.missingFiles()).extracting(MissingFile::catalogFileId).containsExactly(1L, 2L);
		Assertions.assertThat(scan.physicalOnly()).hasSize(2);
	}

	/**
	 * The non-recursive scan is a different code path from the walk, and with an
	 * empty folder its filters never ran: a file in the folder has to be seen, and
	 * one in a subfolder has to be ignored.
	 */
	@Test
	void reconcileShouldSeeOnlyTheTopFolderWhenItIsNotRecursive() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));

		Files.writeString(source.resolve("top.jpg"), "top");
		Files.writeString(Files.createDirectory(source.resolve("sub")).resolve("deep.jpg"), "deep");

		var response = service().reconcile(new OrganizationReconcileRequest(source.toString(), false, false, 10));

		Assertions.assertThat(response.filesOnDisk()).isEqualTo(1);
		Assertions.assertThat(response.missingInDatabaseSamples()).singleElement()
				.satisfies(sample -> Assertions.assertThat(sample.path()).endsWith("top.jpg"));
	}

	/**
	 * A shallow pass reads one folder and no walk tells it anything, so it applies
	 * the exclusions itself. A file it is not meant to see, counted here, would be
	 * reported as something the catalog never heard of - and the repair for that is
	 * to catalogue it.
	 */
	@Test
	void theShallowPassLeavesOutWhatTheDeepOneWouldToo() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));

		Files.writeString(source.resolve("visible.jpg"), "ok");

		Path excluded = Files.writeString(source.resolve("ignored.tmp"), "not for us");

		when(scanExclusionService.isExcluded(any(Path.class), any(Path.class)))
				.thenAnswer(invocation -> invocation.getArgument(1, Path.class).toAbsolutePath().normalize()
						.equals(excluded.toAbsolutePath().normalize()));

		var response = service().reconcile(new OrganizationReconcileRequest(source.toString(), false, false, 10));

		Assertions.assertThat(response.filesOnDisk()).as("the excluded file is not a file this pass is about")
				.isEqualTo(1);
		Assertions.assertThat(response.missingInDatabaseSamples())
				.extracting(OrganizationReconcileIssueResponse::path)
				.doesNotContain(PathUtils.normalize(excluded));
	}

	@Test
	void reconcileShouldIgnoreExcludedDiskAndDatabasePaths() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path git = Files.createDirectory(source.resolve(".git"));
		Path ignoredDisk = Files.writeString(git.resolve("config"), "git");
		Path ignoredDatabase = git.resolve("index");
		Path visible = Files.writeString(source.resolve("visible.jpg"), "ok");

		when(scanExclusionService.isExcluded(any(Path.class), any(Path.class))).thenAnswer(invocation -> {
			Path root = invocation.getArgument(0, Path.class);
			Path candidate = invocation.getArgument(1, Path.class);

			return root.toAbsolutePath().normalize().equals(source.toAbsolutePath().normalize())
					&& candidate.toAbsolutePath().normalize().startsWith(git.toAbsolutePath().normalize());
		});
		List<MediaLocationReconcileProjection> catalogued = List.of(row(1L, visible), row(2L, ignoredDatabase));

		when(catalogFileLocationRepository.findForReconcile(eq(PathUtils.normalize(source)), any(), eq(0L),
				any(Limit.class))).thenReturn(catalogued);

		var response = service().reconcile(new OrganizationReconcileRequest(source.toString(), true, false, 10));

		Assertions.assertThat(response.filesOnDisk()).isEqualTo(1);
		Assertions.assertThat(response.filesInDatabase()).isEqualTo(1);
		Assertions.assertThat(response.missingInDatabaseSamples()).extracting(OrganizationReconcileIssueResponse::path)
				.doesNotContain(PathUtils.normalize(ignoredDisk));
	}

	@Test
	void reconcileShouldNotDescendIntoHiddenDirectoriesWhenHiddenContentIsExcluded() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path visible = Files.writeString(source.resolve("visible.jpg"), "ok");
		Path hidden = Files.createDirectory(source.resolve(".hidden"));

		Files.writeString(hidden.resolve("inside.jpg"), "hidden");

		markHidden(hidden);

		List<MediaLocationReconcileProjection> catalogued = List.of(row(1L, visible));

		when(catalogFileLocationRepository.findForReconcile(eq(PathUtils.normalize(source)), any(), eq(0L),
				any(Limit.class))).thenReturn(catalogued);

		var response = service().reconcile(new OrganizationReconcileRequest(source.toString(), true, false, 10));

		Assertions.assertThat(response.filesOnDisk()).isEqualTo(1);
		Assertions.assertThat(response.missingInDatabase()).isZero();
	}

	@Test
	void reconcileShouldSkipTheQuarantineSubtreeDuringRecursiveWalk() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path visible = Files.writeString(source.resolve("visible.jpg"), "ok");
		Path quarantine = Files.createDirectory(source.resolve("quarantine"));

		Files.writeString(quarantine.resolve("dupe.jpg"), "dupe");

		when(scanExclusionService.isWithinQuarantine(any(Path.class)))
				.thenAnswer(invocation -> invocation.getArgument(0, Path.class).toAbsolutePath().normalize()
						.startsWith(quarantine.toAbsolutePath().normalize()));
		List<MediaLocationReconcileProjection> catalogued = List.of(row(1L, visible));

		when(catalogFileLocationRepository.findForReconcile(eq(PathUtils.normalize(source)), any(), eq(0L),
				any(Limit.class))).thenReturn(catalogued);

		var response = service().reconcile(new OrganizationReconcileRequest(source.toString(), true, false, 10));

		Assertions.assertThat(response.filesOnDisk()).isEqualTo(1);
		Assertions.assertThat(response.missingInDatabase()).isZero();
	}

	@Test
	void reconcileShouldRejectMissingOrNonDirectorySourcesBeforeQueryingDatabase() {
		Path missing = tempDir.resolve("missing");
		Path file = tempDir.resolve("file.txt");

		OrganizationReconcileService service = service();

		OrganizationReconcileRequest missingRequest = request(missing);

		Assertions.assertThatThrownBy(() -> service.reconcile(missingRequest))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not exist");

		try {
			Files.writeString(file, "file");
		} catch (Exception exception) {
			throw new AssertionError(exception);
		}

		OrganizationReconcileRequest fileRequest = request(file);

		Assertions.assertThatThrownBy(() -> service.reconcile(fileRequest)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("not a directory");

		Mockito.verifyNoInteractions(catalogFileLocationRepository);
	}

	/**
	 * The walk is keyed rather than numbered: every round asks for what comes after
	 * the last id it read, and an empty round is what ends it. Two rounds carrying
	 * the same path still count as one file in the database.
	 */
	@Test
	void reconcileShouldReadEveryKeyedPageAndDeduplicatePaths() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("paged"));
		Path missing = source.resolve("missing.jpg");

		String normalized = PathUtils.normalize(source);

		// Shallow, so the pages come from the shallow query: the paging is the same
		// walk either way, and asking the recursive one here would be asking a
		// question this request never asks.
		List<MediaLocationReconcileProjection> firstPage = List.of(row(1L, missing));
		List<MediaLocationReconcileProjection> secondPage = List.of(row(2L, missing));

		when(catalogFileLocationRepository.findForShallowReconcile(eq(normalized), eq(0L), any(Limit.class)))
				.thenReturn(firstPage);
		when(catalogFileLocationRepository.findForShallowReconcile(eq(normalized), eq(1L), any(Limit.class)))
				.thenReturn(secondPage);

		var response = service().reconcile(new OrganizationReconcileRequest(source.toString(), false, false, 10));

		Assertions.assertThat(response.filesInDatabase()).isEqualTo(1);
		Assertions.assertThat(response.missingOnDisk()).isEqualTo(2);
		Assertions.assertThat(response.missingOnDiskSamples()).hasSize(2);
	}

	private void markHidden(Path directory) {
		try {
			Files.setAttribute(directory, "dos:hidden", Boolean.TRUE);
		} catch (UnsupportedOperationException | IllegalArgumentException | IOException _) {
			// Non-DOS file systems (for example POSIX) already treat the dot-prefixed name
			// as hidden, so no explicit attribute is needed there.
		}
	}

	private OrganizationReconcileRequest request(Path path) {
		return new OrganizationReconcileRequest(path.toString(), false, false, 10);
	}

	private OrganizationReconcileService service() {
		return new OrganizationReconcileService(catalogFileLocationRepository, scanExclusionService);
	}

	/**
	 * A catalogued place that still agrees with the disk, which is what almost
	 * every row of almost every pass is: the size and the timestamp are read from
	 * the file itself, so a comparison against them finds nothing to say.
	 *
	 * <p>
	 * And read at the precision the column keeps, because that is how the catalog
	 * would hand the row back. A fixture holding the nanoseconds the filesystem
	 * reports would describe a state that cannot be stored, and the walk would
	 * suspect a file nobody touched.
	 */
	private MediaLocationReconcileProjection row(Long catalogFileId, Path currentPath) {
		try {
			return row(catalogFileId, currentPath, Files.size(currentPath),
					CatalogTimestamp.observed(Files.getLastModifiedTime(currentPath)));
		} catch (IOException _) {
			// The file is not there, which is the case the row is about: the catalog holds
			// what it last knew, and the walk is what will find nothing at the path.
			return row(catalogFileId, currentPath, 1L, Instant.EPOCH);
		}
	}

	private MediaLocationReconcileProjection row(Long catalogFileId, Path currentPath, Long sizeBytes,
			Instant modifiedAt) {
		MediaLocationReconcileProjection row = mock(MediaLocationReconcileProjection.class);

		lenient().when(row.getCatalogFileId()).thenReturn(catalogFileId);
		lenient().when(row.getCurrentPath()).thenReturn(PathUtils.normalize(currentPath));
		lenient().when(row.getSizeBytes()).thenReturn(sizeBytes);
		lenient().when(row.getModifiedAt()).thenReturn(modifiedAt);

		return row;
	}
}