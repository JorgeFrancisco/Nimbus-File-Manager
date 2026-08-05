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
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.FingerprintFailureDetail;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PendingPhoto;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionStatusNames;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.ExternalToolNotRunnableException;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.PhotoPerceptualHashService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.UnsupportedPhotoFingerprintException;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.PhotoPerceptualFingerprint;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ProcessingCoordinator;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ProcessingMetrics;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.ProcessingProperties;

@ExtendWith(MockitoExtension.class)
class PhashBacklogServiceTest {

	@Mock
	private MediaFingerprintRepository mediaFingerprintRepository;

	@Mock
	private FingerprintFailureRepository fingerprintFailureRepository;

	@Mock
	private PhotoPerceptualHashService photoPerceptualHashService;

	@Mock
	private ExecutionRepository executionRepository;

	private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

	private PhashBacklogService service() {
		return new PhashBacklogService(mediaFingerprintRepository, fingerprintFailureRepository,
				photoPerceptualHashService,
				new ProcessingCoordinator(new ProcessingProperties(1, 8, 1, 1, 1, 1), new ProcessingMetrics()),
				executionRepository, transactionManager, Clock.systemDefaultZone());
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
				.<PendingPhoto>mapToObj(index -> new PendingPhoto((long) index, "/tmp/photo" + index + ".jpg"))
				.toList();

		when(mediaFingerprintRepository.findPendingPhotos(eq(PhashBacklogService.KIND),
				eq(DuplicateConstants.ALGORITHM), eq(PhashBacklogService.MAX_ATTEMPTS), any())).thenReturn(sixty,
						List.of());
		when(photoPerceptualHashService.compute(any()))
				.thenReturn(new PhotoPerceptualFingerprint(new byte[32], new byte[1024]));

		service().drainPending(() -> false, (_, _) -> {
		});

		// 60 items in units of 25: three writes, never one of sixty.
		verify(transactionManager, times(3)).getTransaction(any());
	}

	@SuppressWarnings("unchecked")
	@Test
	void drainStoresSuccessesAndRecordsFailures() {
		PendingPhoto good = new PendingPhoto(1L, "/tmp/a.jpg");
		PendingPhoto bad = new PendingPhoto(2L, "/tmp/b.jpg");

		when(mediaFingerprintRepository.findPendingPhotos(eq(PhashBacklogService.KIND),
				eq(DuplicateConstants.ALGORITHM), eq(PhashBacklogService.MAX_ATTEMPTS), any()))
						.thenReturn(List.of(good, bad), List.of());

		byte[] hash = new byte[32];

		hash[0] = 111;

		when(photoPerceptualHashService.compute(Path.of("/tmp/a.jpg")))
				.thenReturn(new PhotoPerceptualFingerprint(hash, new byte[1024]));
		when(photoPerceptualHashService.compute(Path.of("/tmp/b.jpg"))).thenThrow(new IllegalStateException("boom"));
		when(fingerprintFailureRepository.findByCatalogFileIdAndKindAndAlgorithm(eq(2L), any(), any()))
				.thenReturn(Optional.empty());

		DrainResult result = service().drainPending(() -> false, (_, _) -> {
		});

		Assertions.assertThat(result.processed()).isEqualTo(1);
		Assertions.assertThat(result.failed()).isEqualTo(1);

		ArgumentCaptor<MediaFingerprint> fingerprint = ArgumentCaptor.forClass(MediaFingerprint.class);

		verify(mediaFingerprintRepository).save(fingerprint.capture());

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
		});

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
		});

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
		});

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
		PendingPhoto photo = new PendingPhoto(9L, "/tmp/holiday.jpg");

		FingerprintFailureReason reason = service().reason(photo,
				new ExternalToolNotRunnableException("./tools/bin/ffmpeg.exe", new IOException("error=2")));

		Assertions.assertThat(reason).isEqualTo(FingerprintFailureReason.TOOL_UNAVAILABLE);
		Assertions.assertThat(reason.terminal()).isFalse();
	}

	@SuppressWarnings("unchecked")
	@Test
	void unsupportedContainerBecomesTerminalImmediatelyAndIsNotReportedAsRetryableFailure() {
		PendingPhoto sticker = new PendingPhoto(3L, "/tmp/sticker.webp");

		when(mediaFingerprintRepository.findPendingPhotos(eq(PhashBacklogService.KIND),
				eq(DuplicateConstants.ALGORITHM), eq(PhashBacklogService.MAX_ATTEMPTS), any()))
						.thenReturn(List.of(sticker), List.of());
		when(photoPerceptualHashService.compute(Path.of("/tmp/sticker.webp")))
				.thenThrow(new UnsupportedPhotoFingerprintException("ZIP/Lottie"));
		when(fingerprintFailureRepository.findByCatalogFileIdAndKindAndAlgorithm(eq(3L), any(), any()))
				.thenReturn(Optional.empty());

		service().drainPending(() -> false, (_, _) -> {
		});

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

	@Test
	void rebuildDeletesOnlyCurrentDerivedFingerprintsAndFailures() {
		when(mediaFingerprintRepository.deleteByKindAndAlgorithm(PhashBacklogService.KIND,
				DuplicateConstants.ALGORITHM)).thenReturn(12L);

		Assertions.assertThat(service().rebuild()).isEqualTo(12L);

		verify(fingerprintFailureRepository).deleteByKindAndAlgorithm(PhashBacklogService.KIND,
				DuplicateConstants.ALGORITHM);
		verify(mediaFingerprintRepository).deleteByKindAndAlgorithm(PhashBacklogService.KIND,
				DuplicateConstants.ALGORITHM);
	}


	private void active(ExecutionType executionType, boolean active) {
		when(executionRepository.existsByExecutionTypeAndStatusIn(executionType, ExecutionStatusNames.ACTIVE))
				.thenReturn(active);
	}
}