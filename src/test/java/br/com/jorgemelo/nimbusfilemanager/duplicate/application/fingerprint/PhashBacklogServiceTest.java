package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.FingerprintFailure;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintFailureRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PendingPhoto;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.FingerprintFailureDetail;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.PhotoPerceptualHashService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.UnsupportedPhotoFingerprintException;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.PhotoPerceptualFingerprint;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ProcessingCoordinator;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ProcessingMetrics;
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
	private ExecutionQueryService executionQueryService;

	private PhashBacklogService service() {
		return new PhashBacklogService(mediaFingerprintRepository, fingerprintFailureRepository,
				photoPerceptualHashService,
				new ProcessingCoordinator(new ProcessingProperties(1, 8, 1, 1, 1), new ProcessingMetrics()),
				executionQueryService, mock(PlatformTransactionManager.class), Clock.systemDefaultZone());
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
		when(executionQueryService.active()).thenReturn(Optional.empty());

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
		when(executionQueryService.active()).thenReturn(Optional.of(inventoryExecution()));

		DrainResult result = service().drainPending(() -> false, (_, _) -> {
		});

		Assertions.assertThat(result.processed()).isZero();

		verify(mediaFingerprintRepository, never()).findPendingPhotos(any(), any(), anyInt(), any());
	}

	@Test
	void statusDerivesCountsFromTheTables() {
		when(mediaFingerprintRepository.countFingerprintedCatalogFiles(PhashBacklogService.KIND,
				DuplicateConstants.ALGORITHM)).thenReturn(10L);
		when(fingerprintFailureRepository.countExhaustedFailures(PhashBacklogService.KIND,
				DuplicateConstants.ALGORITHM, PhashBacklogService.MAX_ATTEMPTS,
				PhashBacklogService.UNSUPPORTED_PREFIX)).thenReturn(2L);
		when(mediaFingerprintRepository.countPendingPhotos(PhashBacklogService.KIND, DuplicateConstants.ALGORITHM,
				PhashBacklogService.MAX_ATTEMPTS)).thenReturn(5L);

		FingerprintBacklogStatus status = service().status();

		Assertions.assertThat(status.done()).isEqualTo(10);
		Assertions.assertThat(status.failed()).isEqualTo(2);
		Assertions.assertThat(status.pending()).isEqualTo(5);
		Assertions.assertThat(status.total()).isEqualTo(17);
		Assertions.assertThat(status.blocking()).isTrue();
	}

	@Test
	void resetFailuresClearsTheFailureRows() {
		when(fingerprintFailureRepository.deleteRetryableByKindAndAlgorithm(PhashBacklogService.KIND,
				DuplicateConstants.ALGORITHM, PhashBacklogService.UNSUPPORTED_PREFIX)).thenReturn(4L);

		Assertions.assertThat(service().resetFailures()).isEqualTo(4L);
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
		when(executionQueryService.active()).thenReturn(Optional.empty());

		service().drainPending(() -> false, (_, _) -> {
		});

		ArgumentCaptor<FingerprintFailure> failure = ArgumentCaptor.forClass(FingerprintFailure.class);

		verify(fingerprintFailureRepository).save(failure.capture());

		Assertions.assertThat(failure.getValue().getAttempts()).isEqualTo(PhashBacklogService.MAX_ATTEMPTS);
		Assertions.assertThat(failure.getValue().getLastError()).startsWith(PhashBacklogService.UNSUPPORTED_PREFIX);
	}

	@Test
	void failuresReturnsOnlyTheExhaustedRowsWithTheirPaths() {
		List<FingerprintFailureDetail> expected = List
				.of(new FingerprintFailureDetail("C:/photos/broken.jpg", "decode failed"));

		when(fingerprintFailureRepository.findExhaustedWithPath(PhashBacklogService.KIND, DuplicateConstants.ALGORITHM,
				PhashBacklogService.MAX_ATTEMPTS, PhashBacklogService.UNSUPPORTED_PREFIX)).thenReturn(expected);

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

	private ExecutionResponse inventoryExecution() {
		return new ExecutionResponse(1L, "INVENTORY", "PROCESSING_FILES", LocalDateTime.now(), null, "src", null, 1, 1,
				0, 0, 0, 0, null, null, "running", false);
	}
}