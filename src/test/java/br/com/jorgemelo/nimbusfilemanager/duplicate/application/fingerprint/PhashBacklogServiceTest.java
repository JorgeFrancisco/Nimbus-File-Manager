package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DrainResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintFailureReason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.FingerprintFailure;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintFailureRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintRebuildTaskRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.FingerprintFailureDetail;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PendingPhoto;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence.FingerprintWriter;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence.SimilarityRelationWriter;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionStatusNames;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.ExternalToolNotRunnableException;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.PhotoHashGroupMismatchException;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.PhotoPerceptualHashService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.UnsupportedPhotoFingerprintException;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.PhotoPerceptualFingerprint;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ProcessingCoordinator;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.ProcessingProperties;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionMetricsContext;

@ExtendWith(MockitoExtension.class)
class PhashBacklogServiceTest {

	/** This test's own context: nothing here is shared with another run. */
	private final ExecutionMetricsContext metricsContext = new ExecutionMetricsContext();

	/** The generation of bytes the work started from. */
	private static final long REVISION = 1L;

	private final FingerprintWriter fingerprintWriter = mock(FingerprintWriter.class);

	@Mock
	private MediaFingerprintRepository mediaFingerprintRepository;

	@Mock
	private FingerprintFailureRepository fingerprintFailureRepository;

	@Mock
	private PhotoPerceptualHashService photoPerceptualHashService;

	@Mock
	private ExecutionRepository executionRepository;

	@Mock
	private FingerprintRebuildTaskRepository fingerprintRebuildTaskRepository;

	@Mock
	private SimilarityRelationWriter similarityRelationWriter;

	private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

	private PhashBacklogService service() {
		FingerprintBacklogEngine engine = new FingerprintBacklogEngine(mediaFingerprintRepository,
				fingerprintFailureRepository, fingerprintRebuildTaskRepository,
				new ProcessingCoordinator(new ProcessingProperties(1, 8, 1, 1, 1, 1)),
				executionRepository, transactionManager, Clock.systemDefaultZone());

		return new PhashBacklogService(engine, mediaFingerprintRepository, fingerprintFailureRepository,
				fingerprintRebuildTaskRepository, photoPerceptualHashService, similarityRelationWriter,
				fingerprintWriter, Clock.systemDefaultZone());
	}

	/**
	 * Whatever a run computed but had not written dies with the process, and a
	 * fetched batch is two hundred items of ffmpeg. Writing in small units is what
	 * keeps a restart from throwing away a run's whole afternoon, so the batch has
	 * to reach the database in more than one transaction.
	 */
	@SuppressWarnings("unchecked")
	@Test
	void aFetchedBatchIsWrittenInSeveralSmallTransactions() {
		List<PendingPhoto> sixty = IntStream.rangeClosed(1, 60)
				.<PendingPhoto>mapToObj(index -> new PendingPhoto((long) index, "/tmp/photo" + index + ".jpg", 1L))
				.toList();

		when(mediaFingerprintRepository.findPendingPhotos(eq(PhashBacklogService.KIND),
				eq(DuplicateConstants.ALGORITHM), eq(PhashBacklogService.MAX_ATTEMPTS), any())).thenReturn(sixty,
						List.of());
		when(photoPerceptualHashService.computeGroup(any(), any())).thenAnswer(invocation -> oneSamplePerPhoto(
				invocation.getArgument(0)));

		service().drainPending(() -> false, (_, _) -> {
		}, Takings.unfenced(1L), metricsContext);

		// 60 items in units of 25: three writes, never one of sixty.
		verify(transactionManager, times(3)).getTransaction(any());
	}

	/**
	 * A group that came back the wrong length has not lost one photo's sample: it
	 * has lost which photo any of them belongs to, since the only thing pairing the
	 * two is position. So none of it is written and every photo is read again on
	 * its own, where each answer is attributable. What must never happen is the
	 * group being kept minus the sample it came up short of - that would file one
	 * photo's fingerprint under another's name, and nothing downstream could tell.
	 */
	@SuppressWarnings("unchecked")
	@Test
	void aRefusedGroupIsReadOnePhotoAtATimeAndNoPhotoIsLost() {
		List<PendingPhoto> twentyFive = IntStream.rangeClosed(1, 25)
				.<PendingPhoto>mapToObj(index -> new PendingPhoto((long) index, "/tmp/photo" + index + ".jpg",
						REVISION))
				.toList();

		when(mediaFingerprintRepository.findPendingPhotos(eq(PhashBacklogService.KIND),
				eq(DuplicateConstants.ALGORITHM), eq(PhashBacklogService.MAX_ATTEMPTS), any()))
						.thenReturn(twentyFive, List.of());
		when(photoPerceptualHashService.computeGroup(any(), any()))
				.thenThrow(new PhotoHashGroupMismatchException("Expected 25600 bytes, got 24576"));
		when(photoPerceptualHashService.compute(any(), any()))
				.thenReturn(new PhotoPerceptualFingerprint(new byte[32], new byte[1024]));
		when(fingerprintWriter.insertForRevision(any(), eq(REVISION))).thenReturn(true);

		DrainResult result = service().drainPending(() -> false, (_, _) -> {
		}, Takings.unfenced(1L), metricsContext);

		Assertions.assertThat(result.processed()).isEqualTo(25);
		Assertions.assertThat(result.failed()).isZero();

		verify(photoPerceptualHashService, times(25)).compute(any(), any());
		verify(fingerprintWriter, times(25)).insertForRevision(any(), eq(REVISION));
	}

	/**
	 * A stop reaches the groups that had not started yet, and what it leaves them
	 * is nothing at all: no fingerprint, and above all no failure. Writing one
	 * would spend an attempt of a photo that was never read, and three of those
	 * retire it from the queue for good.
	 */
	@SuppressWarnings("unchecked")
	@Test
	void aStopLeavesTheGroupsItReachedPendingRatherThanFailed() {
		List<PendingPhoto> sixty = IntStream.rangeClosed(1, 60)
				.<PendingPhoto>mapToObj(index -> new PendingPhoto((long) index, "/tmp/photo" + index + ".jpg",
						REVISION))
				.toList();

		when(mediaFingerprintRepository.findPendingPhotos(eq(PhashBacklogService.KIND),
				eq(DuplicateConstants.ALGORITHM), eq(PhashBacklogService.MAX_ATTEMPTS), any())).thenReturn(sixty,
						List.of());

		AtomicBoolean stop = new AtomicBoolean();

		// Stops the run from inside the first group, so the ones behind it are still
		// waiting on the pool when the cancellation arrives.
		when(photoPerceptualHashService.computeGroup(any(), any())).thenAnswer(invocation -> {
			stop.set(true);

			return oneSamplePerPhoto(invocation.getArgument(0));
		});

		DrainResult result = service().drainPending(stop::get, (_, _) -> {
		}, Takings.unfenced(1L), metricsContext);

		Assertions.assertThat(result.processed()).isEqualTo(25);
		Assertions.assertThat(result.failed()).isZero();

		verify(fingerprintFailureRepository, never()).save(any());
	}

	private static List<PhotoPerceptualFingerprint> oneSamplePerPhoto(List<Path> files) {
		return files.stream().map(_ -> new PhotoPerceptualFingerprint(new byte[32], new byte[1024])).toList();
	}

	@SuppressWarnings("unchecked")
	@Test
	void drainStoresSuccessesAndRecordsFailures() {
		PendingPhoto good = new PendingPhoto(1L, "/tmp/a.jpg", REVISION);
		PendingPhoto bad = new PendingPhoto(2L, "/tmp/b.jpg", REVISION);

		when(mediaFingerprintRepository.findPendingPhotos(eq(PhashBacklogService.KIND),
				eq(DuplicateConstants.ALGORITHM), eq(PhashBacklogService.MAX_ATTEMPTS), any()))
						.thenReturn(List.of(good, bad), List.of());

		byte[] hash = new byte[32];

		hash[0] = 111;

		// A photo the decoder cannot read takes the whole group down with it, which
		// is how a failure comes to be attributed at all: read alone, it is the only
		// one that fails.
		when(photoPerceptualHashService.computeGroup(any(), any()))
				.thenThrow(new IllegalStateException("Could not run ffmpeg for a group. boom"));
		when(photoPerceptualHashService.compute(eq(Path.of("/tmp/a.jpg")), any()))
				.thenReturn(new PhotoPerceptualFingerprint(hash, new byte[1024]));
		when(photoPerceptualHashService.compute(eq(Path.of("/tmp/b.jpg")), any()))
				.thenThrow(new IllegalStateException("boom"));
		when(fingerprintFailureRepository.findByCatalogFileIdAndKindAndAlgorithm(eq(2L), any(), any()))
				.thenReturn(Optional.empty());

		DrainResult result = service().drainPending(() -> false, (_, _) -> {
		}, Takings.unfenced(1L), metricsContext);

		Assertions.assertThat(result.processed()).isEqualTo(1);
		Assertions.assertThat(result.failed()).isEqualTo(1);

		ArgumentCaptor<MediaFingerprint> fingerprint = ArgumentCaptor.forClass(MediaFingerprint.class);

		// Written for the generation the job read, so an edit landing mid-computation
		// refuses the answer instead of recording one about bytes the catalog dropped.
		verify(fingerprintWriter).insertForRevision(fingerprint.capture(), eq(REVISION));

		Assertions.assertThat(fingerprint.getValue().getCatalogFileId()).isEqualTo(1L);
		Assertions.assertThat(fingerprint.getValue().getHash()).isNull();
		Assertions.assertThat(fingerprint.getValue().getHashBytes()).containsExactly(hash);
		Assertions.assertThat(fingerprint.getValue().getSampleBytes()).hasSize(1024);

		ArgumentCaptor<FingerprintFailure> failure = ArgumentCaptor.forClass(FingerprintFailure.class);

		verify(fingerprintFailureRepository).save(failure.capture());

		Assertions.assertThat(failure.getValue().getCatalogFileId()).isEqualTo(2L);
		Assertions.assertThat(failure.getValue().getAttempts()).isEqualTo(1);
		Assertions.assertThat(failure.getValue().getLastError()).contains("boom");
	}

	@Test
	void drainYieldsToAnActiveInventory() {
		active(ExecutionType.INVENTORY, true);

		DrainResult result = service().drainPending(() -> false, (_, _) -> {
		}, Takings.unfenced(1L), metricsContext);

		Assertions.assertThat(result.processed()).isZero();

		verify(mediaFingerprintRepository, never()).findPendingPhotos(any(), any(), anyInt(), any());
	}

	/**
	 * A conversion needs ffmpeg and the hardware encoder for work the user is
	 * waiting on, and the backlog has nobody waiting on it: it steps aside, and the
	 * run that follows picks the queue up from the database where it left it.
	 */
	@Test
	void drainYieldsToAnActiveConversion() {
		active(ExecutionType.INVENTORY, false);
		active(ExecutionType.CONVERSION, true);

		DrainResult result = service().drainPending(() -> false, (_, _) -> {
		}, Takings.unfenced(1L), metricsContext);

		Assertions.assertThat(result.processed()).isZero();

		verify(mediaFingerprintRepository, never()).findPendingPhotos(any(), any(), anyInt(), any());
	}

	/**
	 * Being told to stop is answered before anything else is asked. It is the
	 * cheapest of the three reasons and the only one the caller already knows, so a
	 * drain that is cancelled on arrival costs neither a query nor a batch.
	 */
	@Test
	void drainStopsWhenItArrivesAlreadyCancelled() {
		DrainResult result = service().drainPending(() -> true, (_, _) -> {
		}, Takings.unfenced(1L), metricsContext);

		Assertions.assertThat(result.processed()).isZero();

		verify(mediaFingerprintRepository, never()).findPendingPhotos(any(), any(), anyInt(), any());
		verify(executionRepository, never()).existsByExecutionTypeAndStatusIn(any(), any());
	}

	/**
	 * A row that has been taken again stops the drain before it fetches anything.
	 *
	 * <p>
	 * Asked per item alongside the other two reasons to stop, and for the same
	 * reason they are: what it saves is minutes of hashing whose results the pin
	 * would refuse to write anyway.
	 */
	@Test
	void drainStopsWhenTheRowHasBeenTakenOver() {
		DrainResult result = service().drainPending(() -> false, (_, _) -> {
		}, Takings.replaced(1L), metricsContext);

		Assertions.assertThat(result.processed()).isZero();

		verify(mediaFingerprintRepository, never()).findPendingPhotos(any(), any(), anyInt(), any());
	}

	/**
	 * Anything else running is none of the backlog's business - and is not even
	 * asked about: the two types it steps aside for are named, so a third one
	 * running is indistinguishable from nothing running.
	 */
	@Test
	void drainKeepsGoingWhileAnUnrelatedExecutionRuns() {
		service().drainPending(() -> false, (_, _) -> {
		}, Takings.unfenced(1L), metricsContext);

		verify(mediaFingerprintRepository).findPendingPhotos(any(), any(), anyInt(), any());
		verify(executionRepository, never()).existsByExecutionTypeAndStatusIn(eq(ExecutionType.ORGANIZATION), any());
	}

	/**
	 * The Duplicados screen shows the inventory's own progress from this flag, so
	 * it has to keep meaning the inventory and nothing else.
	 */
	@Test
	void inventoryActiveStaysAboutTheInventoryAlone() {
		active(ExecutionType.INVENTORY, false);
		active(ExecutionType.CONVERSION, true);

		Assertions.assertThat(service().inventoryActive()).isFalse();
		Assertions.assertThat(service().pausedByActiveExecution()).isTrue();
	}

	/**
	 * The Duplicados screen refuses deletions up front from this flag, so it has to
	 * mean the conversion and nothing else - an inventory blocks the whole screen
	 * for a different reason and says so in its own words.
	 */
	@Test
	void conversionActiveStaysAboutTheConversionAlone() {
		active(ExecutionType.INVENTORY, false);
		active(ExecutionType.CONVERSION, true);

		Assertions.assertThat(service().conversionActive()).isTrue();
		Assertions.assertThat(service().inventoryActive()).isFalse();
	}

	@Test
	void noConversionRunningLeavesDeletionAlone() {
		active(ExecutionType.INVENTORY, true);
		active(ExecutionType.CONVERSION, false);

		Assertions.assertThat(service().inventoryActive()).isTrue();
		Assertions.assertThat(service().conversionActive()).isFalse();
	}

	@Test
	void statusDerivesCountsFromTheTables() {
		when(mediaFingerprintRepository.countFingerprintedCatalogFiles(PhashBacklogService.KIND,
				DuplicateConstants.ALGORITHM)).thenReturn(10L);
		when(fingerprintFailureRepository.countExhaustedFailures(PhashBacklogService.KIND, DuplicateConstants.ALGORITHM,
				PhashBacklogService.MAX_ATTEMPTS)).thenReturn(2L);
		when(mediaFingerprintRepository.countPendingPhotos(PhashBacklogService.KIND, DuplicateConstants.ALGORITHM,
				PhashBacklogService.MAX_ATTEMPTS)).thenReturn(5L);

		FingerprintBacklogStatus status = service().status();

		Assertions.assertThat(status.done()).isEqualTo(10);
		Assertions.assertThat(status.failed()).isEqualTo(2);
		Assertions.assertThat(status.pending()).isEqualTo(5);
		Assertions.assertThat(status.total()).isEqualTo(17);
		Assertions.assertThat(status.blocking()).isTrue();
	}

	/**
	 * Every non-terminal reason goes, not one named by hand. The button used to
	 * clear {@code UNKNOWN} alone, which left a run whose ffmpeg never started with
	 * files no button could return to the queue.
	 */
	@Test
	void resetFailuresClearsEveryReasonARetryCanChange() {
		when(fingerprintFailureRepository.deleteRetryableByKindAndAlgorithm(PhashBacklogService.KIND,
				DuplicateConstants.ALGORITHM, FingerprintFailureReason.retryable())).thenReturn(4L);

		Assertions.assertThat(service().resetFailures()).isEqualTo(4L);
	}

	/**
	 * The decoder never opened the file, so nothing about the file explains this.
	 * Answering before the classifier is what keeps a healthy photo from being
	 * written off for a tool that was not where the settings said.
	 */
	@Test
	void blamesTheToolAndNotTheFileWhenFfmpegCouldNotStart() {
		PendingPhoto photo = new PendingPhoto(9L, "/tmp/holiday.jpg", REVISION);

		FingerprintFailureReason reason = service().reason(photo,
				new ExternalToolNotRunnableException("./tools/bin/ffmpeg.exe", new IOException("error=2")));

		Assertions.assertThat(reason).isEqualTo(FingerprintFailureReason.TOOL_UNAVAILABLE);
		Assertions.assertThat(reason.terminal()).isFalse();
	}

	@SuppressWarnings("unchecked")
	@Test
	void unsupportedContainerBecomesTerminalImmediatelyAndIsNotReportedAsRetryableFailure() {
		PendingPhoto sticker = new PendingPhoto(3L, "/tmp/sticker.webp", REVISION);

		when(mediaFingerprintRepository.findPendingPhotos(eq(PhashBacklogService.KIND),
				eq(DuplicateConstants.ALGORITHM), eq(PhashBacklogService.MAX_ATTEMPTS), any()))
						.thenReturn(List.of(sticker), List.of());
		// The container is refused before any decoding, so it refuses the group it
		// was in as well and each of its photos is then read on its own.
		when(photoPerceptualHashService.computeGroup(any(), any()))
				.thenThrow(new UnsupportedPhotoFingerprintException("ZIP/Lottie"));
		when(photoPerceptualHashService.compute(eq(Path.of("/tmp/sticker.webp")), any()))
				.thenThrow(new UnsupportedPhotoFingerprintException("ZIP/Lottie"));
		when(fingerprintFailureRepository.findByCatalogFileIdAndKindAndAlgorithm(eq(3L), any(), any()))
				.thenReturn(Optional.empty());

		service().drainPending(() -> false, (_, _) -> {
		}, Takings.unfenced(1L), metricsContext);

		ArgumentCaptor<FingerprintFailure> failure = ArgumentCaptor.forClass(FingerprintFailure.class);

		verify(fingerprintFailureRepository).save(failure.capture());

		Assertions.assertThat(failure.getValue().getAttempts()).isEqualTo(PhashBacklogService.MAX_ATTEMPTS);
		Assertions.assertThat(failure.getValue().getReason()).isEqualTo(FingerprintFailureReason.UNSUPPORTED_FORMAT);
	}

	@Test
	void failuresReturnsOnlyTheExhaustedRowsWithTheirPaths() {
		List<FingerprintFailureDetail> expected = List.of(new FingerprintFailureDetail("C:/photos/broken.jpg",
				FingerprintFailureReason.DECODER_REFUSED, "decode failed"));

		when(fingerprintFailureRepository.findExhaustedWithPath(PhashBacklogService.KIND, DuplicateConstants.ALGORITHM,
				PhashBacklogService.MAX_ATTEMPTS)).thenReturn(expected);

		Assertions.assertThat(service().failures()).isSameAs(expected);
	}

	/**
	 * The debts dropped are its own. A rebuild of photos asking about videos would
	 * settle debts the video rebuild is still counting on.
	 */
	@Test
	void discardingWhatIsNoLongerACandidateAsksOnlyAboutItsOwnPhotos() {
		when(fingerprintRebuildTaskRepository.discardIneligiblePhotos(PhashBacklogService.KIND.name(),
				DuplicateConstants.ALGORITHM, FileType.PHOTO.name())).thenReturn(3);

		Assertions.assertThat(service().discardIneligibleRebuildTasks()).isEqualTo(3);
	}

	/**
	 * A rebuild writes down what it owes and gives the failures their attempts
	 * back. It discards nothing: the fingerprints it is going to replace are what
	 * the library answers with until each one is replaced.
	 */
	@Test
	void seedingARebuildOwesEveryPhotoAndDiscardsNothing() {
		when(fingerprintRebuildTaskRepository.seedPhotos(eq(PhashBacklogService.KIND.name()),
				eq(DuplicateConstants.ALGORITHM), any())).thenReturn(12);

		Assertions.assertThat(service().seedRebuild(Takings.unfenced(1L)).orElseThrow()).isEqualTo(12L);

		verify(fingerprintFailureRepository).restoreAttemptBudget(PhashBacklogService.KIND,
				DuplicateConstants.ALGORITHM);
		verify(mediaFingerprintRepository, never()).deleteByCatalogFileIdAndKindAndAlgorithm(any(), any(), any());
	}

	private void active(ExecutionType executionType, boolean active) {
		when(executionRepository.existsByExecutionTypeAndStatusIn(executionType, ExecutionStatusNames.ACTIVE))
				.thenReturn(active);
	}
}