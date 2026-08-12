package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.CatalogTimestamp;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.ContentVerificationLauncher;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentSuspect;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.EligibilityAnnouncer;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.GrantingOperationLocks;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.projection.MediaLocationReconcileProjection;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.CatalogFactProvenance;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.LocationChange;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.KnownContentBatchRow;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.CatalogLifecycleWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.CatalogLocationWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * The reconcile that writes: what the pass does about each thing the comparison
 * found.
 *
 * <p>
 * Three different answers, and the order between them is the point. A file
 * whose bytes turned up somewhere else was never missing, so recognition runs
 * before anything is written off; what is left is recorded as gone; and a path
 * nobody catalogued is handed to the capability that catalogues, because this
 * one never does.
 */
@ExtendWith(MockitoExtension.class)
class OrganizationReconcileApplyTest {

	@TempDir
	Path tempDir;

	@Mock
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Mock
	private ScanExclusionService scanExclusionService;

	@Mock
	private CatalogFileRepository catalogFileRepository;

	@Mock
	private FileHashService fileHashService;

	@Mock
	private CatalogLocationWriter catalogLocationWriter;

	@Mock
	private CatalogLifecycleWriter catalogLifecycleWriter;

	@Mock
	private ExecutionEnqueueService executionEnqueueService;

	@Mock
	private ContentVerificationLauncher contentVerificationLauncher;

	@Mock
	private EligibilityAnnouncer eligibilityAnnouncer;

	private final OperationLockService operationLockService = GrantingOperationLocks.granting();

	/**
	 * The one thing a poor source lets the catalog conclude: those bytes were at
	 * that path, they are at this one, and nothing else in the library claims them.
	 * The evidence says exactly that and no more - it is not proof of a rename,
	 * because a copy followed by a deletion leaves the same trace.
	 */
	@Test
	void recognisesALostFileByItsBytesWhenNothingElseClaimsThem() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path left = source.resolve("old-name.jpg");
		Path arrived = Files.writeString(source.resolve("new-name.jpg"), "hello");

		catalogHolds(source, row(1L, left));

		List<KnownContentBatchRow> known = List.of(knownContent(1L, left, "sha-a", Files.size(arrived)));

		when(catalogFileLocationRepository.findKnownContentByPaths(any(), any())).thenReturn(known);
		when(catalogFileRepository.digestsHeldMoreThanOnce(any())).thenReturn(List.of());
		when(fileHashService.sha256(arrived.toAbsolutePath().normalize())).thenReturn("sha-a");

		applyingPass().reconcileAndApply(request(source));

		ArgumentCaptor<LocationChange> applied = ArgumentCaptor.forClass(LocationChange.class);

		verify(catalogLocationWriter).relocate(applied.capture());

		Assertions.assertThat(applied.getValue().catalogFileId()).isEqualTo(1L);
		Assertions.assertThat(applied.getValue().expectedCurrentPath()).isEqualTo(left.toAbsolutePath().normalize());
		Assertions.assertThat(applied.getValue().newPath()).isEqualTo(arrived.toAbsolutePath().normalize());
		Assertions.assertThat(applied.getValue().provenance().source()).isEqualTo(CatalogEventSources.RECONCILE);
		Assertions.assertThat(applied.getValue().provenance().evidence())
				.isEqualTo(CatalogEventEvidence.SOLE_CONTENT_MATCH);

		// It was never missing, so nothing records it as such - and the path holding it
		// is not handed to an inventory, which would catalogue the same photograph a
		// second time under a new identity.
		verify(catalogLifecycleWriter, never()).markMissing(any(), any());
		verify(executionEnqueueService, never()).enqueue(any());
	}

	/**
	 * The condition that keeps a library of exact duplicates from being merged into
	 * itself: a digest another catalogued file also holds proves nothing about
	 * where anything went.
	 */
	@Test
	void concludesNothingWhenSomeOtherCataloguedFileHoldsTheSameBytes() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path left = source.resolve("old-name.jpg");
		Path arrived = Files.writeString(source.resolve("new-name.jpg"), "hello");

		catalogHolds(source, row(1L, left));

		List<KnownContentBatchRow> known = List.of(knownContent(1L, left, "sha-a", Files.size(arrived)));

		when(catalogFileLocationRepository.findKnownContentByPaths(any(), any())).thenReturn(known);
		when(catalogFileRepository.digestsHeldMoreThanOnce(any())).thenReturn(List.of("sha-a"));

		applyingPass().reconcileAndApply(request(source));

		verify(catalogLocationWriter, never()).relocate(any());
		verify(catalogLifecycleWriter).markMissing(eq(List.of(1L)), any());
	}

	/** Two lost files with the same bytes cannot say which of them arrived. */
	@Test
	void concludesNothingWhenTwoLostFilesShareTheSameBytes() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path leftA = source.resolve("old-a.jpg");
		Path leftB = source.resolve("old-b.jpg");
		Path arrived = Files.writeString(source.resolve("new.jpg"), "hello");

		catalogHolds(source, row(1L, leftA), row(2L, leftB));

		List<KnownContentBatchRow> known = List.of(knownContent(1L, leftA, "sha-shared", Files.size(arrived)),
				knownContent(2L, leftB, "sha-shared", Files.size(arrived)));

		when(catalogFileLocationRepository.findKnownContentByPaths(any(), any())).thenReturn(known);

		applyingPass().reconcileAndApply(request(source));

		verify(catalogLocationWriter, never()).relocate(any());
		verify(catalogLifecycleWriter).markMissing(eq(List.of(1L, 2L)), any());
	}

	/**
	 * Missing is a statement about the file system and nothing else, so the fact
	 * says what was looking and on what grounds: an empty path, and a pass that
	 * caused none of it.
	 */
	@Test
	void recordsWhatTheWalkCouldNotFindAsMissingAndSaysWhy() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path gone = source.resolve("gone.jpg");

		catalogHolds(source, row(1L, gone));

		when(catalogLifecycleWriter.markMissing(any(), any())).thenReturn(1);

		OrganizationReconcileResponse response = applyingPass().reconcileAndApply(request(source));

		ArgumentCaptor<CatalogFactProvenance> told = ArgumentCaptor.captor();

		verify(catalogLifecycleWriter).markMissing(eq(List.of(1L)), told.capture());

		Assertions.assertThat(told.getValue().source()).isEqualTo(CatalogEventSources.RECONCILE);
		Assertions.assertThat(told.getValue().evidence()).isEqualTo(CatalogEventEvidence.PATH_NOT_FOUND);
		Assertions.assertThat(told.getValue().identity()).isNull();

		Assertions.assertThat(response.markedMissing()).isEqualTo(1);

		// Files leaving the analysed set changes what every published grouping was
		// computed over, and it is said once for the pass however many it marked.
		verify(eligibilityAnnouncer).announce("reconcile");
	}

	/**
	 * The pass that agreed with the disk, which is nearly every one of them:
	 * nothing was recognised, recorded or asked for, so there is nothing to bring
	 * up to date.
	 */
	@Test
	void aPassThatFoundNothingToCorrectAsksForNothing() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path settled = Files.writeString(source.resolve("photo.jpg"), "content");

		catalogHolds(source, row(1L, settled));

		applyingPass().reconcileAndApply(request(source));

		verifyNoInteractions(eligibilityAnnouncer, catalogLifecycleWriter, executionEnqueueService,
				contentVerificationLauncher, catalogLocationWriter);
	}

	/**
	 * A file that appears while its notification is lost used to stay invisible
	 * until somebody restarted the application: the walk counted it on every round
	 * and told nobody who could act on it. Asking for an inventory is the whole of
	 * what turns this pass from a report into a safety net - and asking is all it
	 * does, because cataloguing is what an inventory is.
	 */
	@Test
	void asksForAnInventoryOfPathsNobodyCatalogued() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));

		Files.writeString(source.resolve("stranger.jpg"), "who is this");

		catalogHolds(source);

		applyingPass().reconcileAndApply(request(source));

		ArgumentCaptor<List<Execution>> queued = ArgumentCaptor.captor();

		// One admission for the pass, whatever it found - which is what lets the locks
		// behind it be taken in a single order.
		verify(executionEnqueueService).enqueueAll(queued.capture());

		Assertions.assertThat(queued.getValue()).singleElement()
				.satisfies(request -> Assertions.assertThat(request.getExecutionType())
						.isEqualTo(ExecutionType.INVENTORY));

		Assertions.assertThat(queued.getValue().getFirst().getSourcePath()).isEqualTo(PathUtils.normalize(source));
		Assertions.assertThat(queued.getValue().getFirst().getTriggerEvent()).isEqualTo(ExecutionTrigger.TIMER);
	}

	/**
	 * A file still where the catalog has it, holding something it may not have
	 * seen. The pass does not read it - a digest is the only thing that settles
	 * whether the bytes differ, and reading a library of them on the thread that
	 * walks it would turn a comparison into a full re-hash.
	 *
	 * <p>
	 * <b>It asks about a place, and the assertion is on the place.</b> This test
	 * used to require the path to be {@code null}, which is what a verification
	 * cannot be asked with: the type is excluded by path, so the dispatcher failed
	 * every one of them before it could run - 7.354 of them in a single afternoon,
	 * none ever claimed, the divergence never converging and every pass raising it
	 * again. The defect was not missing from the tests; it was written into one.
	 */
	@Test
	void asksForAVerificationOfWhatChangedWithoutReadingIt() throws Exception {
		Path source = Files.createDirectory(tempDir.resolve("source"));
		Path edited = Files.writeString(source.resolve("edited.jpg"), "longer than it was");

		catalogHolds(source, row(4L, edited, 1L, Instant.EPOCH));

		applyingPass().reconcileAndApply(request(source));

		ArgumentCaptor<List<ContentSuspect>> asked = ArgumentCaptor.captor();

		verify(contentVerificationLauncher).requestsFor(asked.capture(), any(), eq(ExecutionTrigger.TIMER));

		Assertions.assertThat(asked.getValue())
				.containsExactly(new ContentSuspect(4L, PathUtils.normalize(edited)));

		verifyNoInteractions(fileHashService);

		// It is there, so it is not gone; and it is catalogued, so it is not new.
		verify(catalogLifecycleWriter, never()).markMissing(any(), any());
	}

	@Test
	void reconcileAndApplyIsDeferredWithoutTouchingTheCatalogWhenTheTreeIsLocked() {
		Path source = tempDir.resolve("source");

		OperationLockService lockService = mock(OperationLockService.class);

		when(lockService.acquire(any(), any())).thenThrow(new OperationLockException("busy"));

		OrganizationReconcileApply locked = new OrganizationReconcileApply(service(), lockService, recovery(),
				convergence());

		OrganizationReconcileResponse response = locked.reconcileAndApply(request(source));

		// Deferred: nothing was scanned and nothing in the catalog was mutated.
		Assertions.assertThat(response.filesOnDisk()).isZero();
		Assertions.assertThat(response.missingOnDisk()).isZero();

		verify(catalogLifecycleWriter, never()).markMissing(any(), any());
		verify(catalogFileLocationRepository, never()).findForReconcile(any(), any(), anyLong(), any());
	}

	private void catalogHolds(Path source, MediaLocationReconcileProjection... rows) {
		when(catalogFileLocationRepository.findForReconcile(eq(PathUtils.normalize(source)), any(), eq(0L),
				any(Limit.class))).thenReturn(List.of(rows));
	}

	private OrganizationReconcileRequest request(Path source) {
		return new OrganizationReconcileRequest(source.toString(), true, false, 10);
	}

	private OrganizationReconcileService service() {
		return new OrganizationReconcileService(catalogFileLocationRepository, scanExclusionService);
	}

	/** The applying pass, which is worker-side and holds the writers. */
	private OrganizationReconcileApply applyingPass() {
		return new OrganizationReconcileApply(service(), operationLockService, recovery(), convergence());
	}

	private RelocationByContent recovery() {
		return new RelocationByContent(catalogFileLocationRepository, catalogFileRepository, catalogLocationWriter,
				fileHashService, Clock.systemDefaultZone());
	}

	private ReconcileConvergence convergence() {
		return new ReconcileConvergence(catalogLifecycleWriter, executionEnqueueService, eligibilityAnnouncer,
				contentVerificationLauncher, Clock.systemDefaultZone());
	}

	/**
	 * A row as the catalog would hand it back, which means the instant at the
	 * precision the column keeps. A fixture holding the nanoseconds the filesystem
	 * reports would describe a state that cannot be stored, and the pass would
	 * report a change nobody made.
	 */
	private MediaLocationReconcileProjection row(Long catalogFileId, Path currentPath) {
		try {
			return row(catalogFileId, currentPath, Files.size(currentPath),
					CatalogTimestamp.observed(Files.getLastModifiedTime(currentPath)));
		} catch (IOException _) {
			// The file is not there, which is the case this row is about.
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

	/**
	 * What the catalog holds about a file the walk could not find - the digest is
	 * the whole of what a poor source has to recognise it by.
	 */
	private KnownContentBatchRow knownContent(Long catalogFileId, Path path, String sha256, Long sizeBytes) {
		KnownContentBatchRow row = mock(KnownContentBatchRow.class);

		lenient().when(row.getCatalogFileId()).thenReturn(catalogFileId);
		lenient().when(row.getInputPath()).thenReturn(PathUtils.normalize(path));
		lenient().when(row.getSha256()).thenReturn(sha256);
		lenient().when(row.getSizeBytes()).thenReturn(sizeBytes);

		return row;
	}
}