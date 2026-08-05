package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntConsumer;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.execution.application.NoCancellations;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.constants.ConversionConstants;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.CommitResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionFileResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionOptions;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionTotals;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.TranscodeRequest;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.TranscodeResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.AudioHandling;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionFailure;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionOutcome;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionQuality;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.NameAffixPosition;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.OriginalDisposition;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.repository.ConversionCandidateRepository;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.repository.projection.ConversionSource;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLock;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.ResolvedMediaDate;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

class VideoConversionServiceTest {

	private final CatalogFileRepository catalogFileRepository = mock(CatalogFileRepository.class);
	private final ConversionCandidateRepository conversionCandidateRepository = mock(
			ConversionCandidateRepository.class);
	private final VideoTranscoder videoTranscoder = mock(VideoTranscoder.class);
	private final ConversionCommitService conversionCommitService = mock(ConversionCommitService.class);
	private final ConversionExecutionRecorder conversionExecutionRecorder = mock(ConversionExecutionRecorder.class);
	private final OperationLockService operationLockService = mock(OperationLockService.class);
	private final OperationLock operationLock = mock(OperationLock.class);
	private final ExecutionCancellationService executionCancellationService = NoCancellations.none();
	private final ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);

	private final VideoConversionService service = new VideoConversionService(catalogFileRepository,
			conversionCandidateRepository, videoTranscoder, conversionCommitService, conversionExecutionRecorder,
			executionProgressService, operationLockService, executionCancellationService);

	private final Execution execution = mock(Execution.class);
	private final UUID mediaId = UUID.randomUUID();

	VideoConversionServiceTest() {
		when(operationLockService.acquireWithin(eq(ConversionConstants.LOCK_WAIT), eq(ExecutionType.CONVERSION),
				any(Path[].class))).thenReturn(operationLock);
		when(execution.getId()).thenReturn(1L);
	}

	@Test
	void reportsNothingSelectedWithoutStartingAnExecution() {
		ConversionResult result = service.convert(List.of(), ConversionOptions.defaults(), execution, owning());

		Assertions.assertThat(result.configured()).isTrue();
		Assertions.assertThat(result.total()).isZero();
	}

	@Test
	void refusesToQuarantineOriginalsWhileNoQuarantineFolderIsConfigured() {
		when(conversionCommitService.quarantineRoot()).thenReturn(Optional.empty());

		ConversionResult result = service
				.convert(List.of(mediaId),
						new ConversionOptions(ConversionQuality.BALANCED, AudioHandling.AUTO,
								OriginalDisposition.QUARANTINE, "", NameAffixPosition.SUFFIX),
						execution, owning());

		Assertions.assertThat(result.configured()).isFalse();

		verify(catalogFileRepository, never()).findByPublicIdIn(any());
		verify(videoTranscoder, never()).transcode(any(), any(), any());
	}

	@Test
	void convertsTheSelectedVideoAndReportsWhatWasSaved(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "0123456789");
		Path converted = Files.writeString(tmp.resolve("clip (H.265).mp4"), "0123");

		stubFile(source, 10L);
		stubSource("h264", 120.0);

		when(videoTranscoder.transcode(any(), any(), any()))
				.thenReturn(TranscodeResult.converted(converted, false, false, false, 4_000));
		when(conversionCommitService.commit(any(), any(), eq(converted), isNull(), any(), any(), any()))
				.thenReturn(CommitResult.committed(converted, false));

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), execution, owning());

		Assertions.assertThat(result.converted()).isEqualTo(1);
		Assertions.assertThat(result.errors()).isZero();
		Assertions.assertThat(result.originalBytes()).isEqualTo(10);
		Assertions.assertThat(result.convertedBytes()).isEqualTo(4);
		Assertions.assertThat(result.savedBytes()).isEqualTo(6);
		Assertions.assertThat(result.savedPercent()).isEqualTo(60);
		Assertions.assertThat(result.items()).singleElement().extracting(ConversionFileResult::outcome)
				.isEqualTo(ConversionOutcome.CONVERTED);

		verify(operationLock).close();
	}

	@Test
	void keepsTheOriginalAndCountsAnErrorWhenTheEncodeFails(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "0123456789");

		stubFile(source, 10L);
		stubSource("h264", 120.0);

		when(videoTranscoder.transcode(any(), any(), any()))
				.thenReturn(TranscodeResult.failed(ConversionFailure.ENCODER_FAILED, false, false, false, 1_000));

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), execution, owning());

		Assertions.assertThat(result.errors()).isEqualTo(1);
		Assertions.assertThat(result.converted()).isZero();
		Assertions.assertThat(source).exists();
		Assertions.assertThat(result.items().getFirst().message()).contains("O original foi mantido");

		verify(conversionCommitService, never()).commit(any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void countsAFailedPlacementAsAnErrorRatherThanAConversion(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "0123456789");
		Path converted = Files.writeString(tmp.resolve("clip (H.265).mp4"), "0123");

		stubFile(source, 10L);
		stubSource("h264", 120.0);

		when(videoTranscoder.transcode(any(), any(), any()))
				.thenReturn(TranscodeResult.converted(converted, false, false, false, 4_000));
		when(conversionCommitService.commit(any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(CommitResult.failed(ConversionFailure.PLACEMENT_FAILED));

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), execution, owning());

		Assertions.assertThat(result.errors()).isEqualTo(1);
		Assertions.assertThat(result.converted()).isZero();
	}

	@Test
	void skipsAVideoThatIsAlreadyAnHevcMp4WithoutEncodingAnything(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "data");

		stubFile(source, 4L);
		stubSource("hevc", 120.0);

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), execution, owning());

		Assertions.assertThat(result.skipped()).isEqualTo(1);
		Assertions.assertThat(result.items().getFirst().outcome()).isEqualTo(ConversionOutcome.SKIPPED);

		verify(videoTranscoder, never()).transcode(any(), any(), any());
	}

	@Test
	void stillRemuxesAnHevcVideoThatIsNotMp4Yet(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mkv"), "0123456789");
		Path converted = Files.writeString(tmp.resolve("clip.mp4"), "0123456789");

		stubFile(source, 10L);
		stubSource("hevc", 120.0);

		when(videoTranscoder.transcode(any(), any(), any()))
				.thenReturn(TranscodeResult.converted(converted, false, false, false, 5));
		when(conversionCommitService.commit(any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(CommitResult.committed(converted, false));

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), execution, owning());

		Assertions.assertThat(result.converted()).isEqualTo(1);

		// The video stream is copied, not re-encoded: only the container changes.
		verify(videoTranscoder).transcode(argThat(TranscodeRequest::sourceIsHevc), any(), any());
	}

	@Test
	void skipsAFileThatIsNoLongerOnDisk(@TempDir Path tmp) {
		stubFile(tmp.resolve("gone.mp4"), 10L);
		stubSource("h264", 120.0);

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), execution, owning());

		Assertions.assertThat(result.skipped()).isEqualTo(1);

		verify(videoTranscoder, never()).transcode(any(), any(), any());
	}

	@Test
	void skipsAnEntryThatIsNoLongerActiveOrIsNotAVideo(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "data");

		CatalogFile deleted = file(source, 4L);

		deleted.setLifecycleStatus(LifecycleStatus.DELETED);

		CatalogFile photo = file(source, 4L);

		photo.setFileType(FileType.PHOTO);

		when(catalogFileRepository.findByPublicIdIn(any())).thenReturn(List.of(deleted, photo));
		stubSource("h264", 120.0);

		ConversionResult result = service.convert(List.of(mediaId, UUID.randomUUID()), ConversionOptions.defaults(),
				execution, owning());

		Assertions.assertThat(result.skipped()).isEqualTo(2);

		verify(videoTranscoder, never()).transcode(any(), any(), any());
	}

	@Test
	void skipsAShortcutInsteadOfConvertingWhatItPointsAt(@TempDir Path tmp) throws Exception {
		Path shortcut = Files.writeString(tmp.resolve("clip.lnk"), "shortcut");

		stubFile(shortcut, 10L);
		stubSource("h264", 120.0);

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), execution, owning());

		Assertions.assertThat(result.skipped()).isEqualTo(1);

		verify(videoTranscoder, never()).transcode(any(), any(), any());
	}

	@Test
	void countsRequestedIdsWithNoCatalogEntryAsSkippedSoTotalsAddUp(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "0123456789");
		Path converted = Files.writeString(tmp.resolve("clip (H.265).mp4"), "0123");

		stubFile(source, 10L);
		stubSource("h264", 120.0);

		when(videoTranscoder.transcode(any(), any(), any()))
				.thenReturn(TranscodeResult.converted(converted, false, false, false, 100));
		when(conversionCommitService.commit(any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(CommitResult.committed(converted, false));

		ConversionResult result = service.convert(List.of(mediaId, UUID.randomUUID()), ConversionOptions.defaults(),
				execution, owning());

		Assertions.assertThat(result.total()).isEqualTo(2);
		Assertions.assertThat(result.converted() + result.skipped() + result.errors()).isEqualTo(result.total());
	}

	@Test
	void locksEveryFileAndTheQuarantineRootWhileItRuns(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "0123456789");
		Path quarantineRoot = tmp.resolve("trash");

		stubFile(source, 10L);
		stubSource("h264", 120.0);

		when(conversionCommitService.quarantineRoot()).thenReturn(Optional.of(quarantineRoot));
		when(videoTranscoder.transcode(any(), any(), any()))
				.thenReturn(TranscodeResult.failed(ConversionFailure.ENCODER_FAILED, false, false, false, 1));

		service.convert(List.of(mediaId), new ConversionOptions(ConversionQuality.BALANCED, AudioHandling.AUTO,
				OriginalDisposition.QUARANTINE, "", NameAffixPosition.SUFFIX), execution, owning());

		ArgumentCaptor<Path[]> locked = ArgumentCaptor.forClass(Path[].class);

		verify(operationLockService).acquireWithin(eq(ConversionConstants.LOCK_WAIT), eq(ExecutionType.CONVERSION),
				locked.capture());

		Assertions.assertThat(locked.getValue()).containsExactlyInAnyOrder(quarantineRoot, source);
	}

	@Test
	void refusesToRunWhenAnotherOperationHoldsThePaths(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "data");

		stubFile(source, 4L);

		when(operationLockService.acquireWithin(eq(ConversionConstants.LOCK_WAIT), eq(ExecutionType.CONVERSION),
				any(Path[].class))).thenThrow(new OperationLockException("busy"));

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), execution, owning());

		Assertions.assertThat(result.total()).isZero();
		Assertions.assertThat(result.message()).contains("Outra opera");

		verify(videoTranscoder, never()).transcode(any(), any(), any());
	}

	@Test
	void reportsBothFileAndBatchProgressWhileItRuns(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "0123456789");
		Path converted = Files.writeString(tmp.resolve("clip (H.265).mp4"), "0123");

		stubFile(source, 10L);
		stubSource("h264", 120.0);

		when(videoTranscoder.transcode(any(), any(), any())).thenAnswer(invocation -> {
			invocation.getArgument(1, IntConsumer.class).accept(40);

			return TranscodeResult.converted(converted, false, false, false, 100);
		});
		when(conversionCommitService.commit(any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(CommitResult.committed(converted, false));

		service.convert(List.of(mediaId), ConversionOptions.defaults(), execution, owning());

		// Both levels of the bar reach the row: how far into the encode ffmpeg said it
		// was, and the file having been finished.
		verify(executionProgressService).updateCurrentItem(execution, 40);
		verify(executionProgressService, atLeastOnce()).updateLiveProgress(eq(execution), anyInt(), eq(1), anyInt(),
				anyInt(), any());
	}

	@Test
	void recordsTheExecutionTotalsWhenTheBatchEnds(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "0123456789");
		Path converted = Files.writeString(tmp.resolve("clip (H.265).mp4"), "0123");

		stubFile(source, 10L);
		stubSource("h264", 120.0);

		when(videoTranscoder.transcode(any(), any(), any()))
				.thenReturn(TranscodeResult.converted(converted, true, false, false, 100));
		when(conversionCommitService.commit(any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(CommitResult.committed(converted, true));

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), execution, owning());

		ArgumentCaptor<ConversionTotals> totals = ArgumentCaptor.forClass(ConversionTotals.class);

		verify(conversionExecutionRecorder).finish(eq(execution), totals.capture(), any(), anyBoolean());

		Assertions.assertThat(totals.getValue().converted()).isEqualTo(1);
		Assertions.assertThat(totals.getValue().savedBytes()).isEqualTo(6);
		Assertions.assertThat(result.items().getFirst().adjustments().audioFallback()).isTrue();
		Assertions.assertThat(result.items().getFirst().originalQuarantined()).isTrue();
	}

	@Test
	void reportsTheWarningWhenTheOriginalCouldNotBeQuarantined(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "0123456789");
		Path converted = Files.writeString(tmp.resolve("clip (H.265).mp4"), "0123");

		stubFile(source, 10L);
		stubSource("h264", 120.0);

		when(conversionCommitService.quarantineRoot()).thenReturn(Optional.of(tmp.resolve("trash")));
		when(videoTranscoder.transcode(any(), any(), any()))
				.thenReturn(TranscodeResult.converted(converted, false, false, false, 100));
		when(conversionCommitService.commit(any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(CommitResult.partial(converted, false, ConversionFailure.QUARANTINE_FAILED));

		ConversionResult result = service
				.convert(List.of(mediaId),
						new ConversionOptions(ConversionQuality.BALANCED, AudioHandling.AUTO,
								OriginalDisposition.QUARANTINE, "", NameAffixPosition.SUFFIX),
						execution, owning());

		Assertions.assertThat(result.converted()).isEqualTo(1);
		Assertions.assertThat(result.items().getFirst().message()).contains("quarentena");
	}

	@Test
	void fallsBackToTheRecommendedOptionsWhenNoneAreGiven(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "data");

		stubFile(source, 4L);
		stubSource("h264", 120.0);

		when(videoTranscoder.transcode(any(), any(), any()))
				.thenReturn(TranscodeResult.failed(ConversionFailure.ENCODER_FAILED, false, false, false, 1));

		service.convert(List.of(mediaId), null, execution, owning());

		verify(videoTranscoder).transcode(argThat(request -> request.options().quality() == ConversionQuality.BALANCED
				&& request.options().audio() == AudioHandling.AUTO), any(), any());
	}

	@ParameterizedTest
	@EnumSource(ConversionFailure.class)
	void explainsEveryFailureCodeInTheUsersLanguage(ConversionFailure failure, @TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "0123456789");

		stubFile(source, 10L);
		stubSource("h264", 120.0);

		when(videoTranscoder.transcode(any(), any(), any()))
				.thenReturn(TranscodeResult.failed(failure, false, false, false, 1));

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), execution, owning());

		Assertions.assertThat(result.items().getFirst().message()).isNotBlank().doesNotContain("backend.conversion");
	}

	@Test
	void convertsAVideoWhoseCodecWasNeverExtracted(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "0123456789");
		Path converted = Files.writeString(tmp.resolve("clip (H.265).mp4"), "0123");

		stubFile(source, 10L);
		stubSource(null, null);

		when(videoTranscoder.transcode(any(), any(), any()))
				.thenReturn(TranscodeResult.converted(converted, false, false, false, 100));
		when(conversionCommitService.commit(any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(CommitResult.committed(converted, false));

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), execution, owning());

		Assertions.assertThat(result.converted()).isEqualTo(1);
	}

	@Test
	void convertsAFileTheCatalogHasNoStreamFactsFor(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "0123456789");
		Path converted = Files.writeString(tmp.resolve("clip (H.265).mp4"), "0123");

		stubFile(source, 10L);

		when(conversionCandidateRepository.findSourcesByPublicIdIn(any())).thenReturn(List.of());
		when(videoTranscoder.transcode(any(), any(), any()))
				.thenReturn(TranscodeResult.converted(converted, false, false, false, 100));
		when(conversionCommitService.commit(any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(CommitResult.committed(converted, false));

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), execution, owning());

		Assertions.assertThat(result.converted()).isEqualTo(1);
	}

	@Test
	void reportsNoSavingWhenTheConvertedFileCanNoLongerBeMeasured(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "0123456789");
		Path converted = tmp.resolve("vanished.mp4");

		stubFile(source, 10L);
		stubSource("h264", 120.0);

		when(videoTranscoder.transcode(any(), any(), any()))
				.thenReturn(TranscodeResult.converted(converted, false, false, false, 100));
		when(conversionCommitService.commit(any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(CommitResult.committed(converted, false));

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), execution, owning());

		Assertions.assertThat(result.converted()).isEqualTo(1);
		Assertions.assertThat(result.convertedBytes()).isZero();
	}

	@Test
	void reportsNoSavingWhenTheCatalogNeverRecordedTheOriginalSize(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "0123456789");
		Path converted = Files.writeString(tmp.resolve("clip (H.265).mp4"), "0123456789012");

		CatalogFile file = file(source, 0L);

		file.setSizeBytes(null);

		when(catalogFileRepository.findByPublicIdIn(any())).thenReturn(List.of(file));
		stubSource("h264", 120.0);

		when(videoTranscoder.transcode(any(), any(), any()))
				.thenReturn(TranscodeResult.converted(converted, false, false, false, 100));
		when(conversionCommitService.commit(any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(CommitResult.committed(converted, false));

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), execution, owning());

		Assertions.assertThat(result.savedBytes()).isNegative();
		Assertions.assertThat(result.savedPercent()).isZero();
	}

	@Test
	void stopsBeforeTheNextFileOnceTheBatchIsCancelled(@TempDir Path tmp) throws Exception {
		Path first = Files.writeString(tmp.resolve("first.mp4"), "0123456789");

		CatalogFile other = file(Files.writeString(tmp.resolve("second.mp4"), "0123456789"), 10L);

		when(catalogFileRepository.findByPublicIdIn(any())).thenReturn(List.of(file(first, 10L), other));
		stubSource("h264", 120.0);

		// Cancelled while the first file is being encoded, which is what the user does.
		when(videoTranscoder.transcode(any(), any(), any())).thenAnswer(_ -> {
			executionCancellationService.requestCancellation(1L);

			return TranscodeResult.failed(ConversionFailure.CANCELLED, false, false, false, 10);
		});

		ConversionResult result = service.convert(List.of(mediaId, UUID.randomUUID()), ConversionOptions.defaults(),
				execution, owning());

		// The file being encoded is reported as cancelled and the next one is never
		// started, so only one item comes back for two requested ids.
		Assertions.assertThat(result.items()).singleElement().extracting(ConversionFileResult::outcome)
				.isEqualTo(ConversionOutcome.CANCELLED);
		Assertions.assertThat(result.converted()).isZero();
		Assertions.assertThat(result.errors()).isZero();

		verify(videoTranscoder).transcode(any(), any(), any());
	}

	@Test
	void recordsACancelledBatchAsCancelledInTheHistory(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "0123456789");

		stubFile(source, 10L);
		stubSource("h264", 120.0);

		when(videoTranscoder.transcode(any(), any(), any()))
				.thenReturn(TranscodeResult.failed(ConversionFailure.CANCELLED, false, false, false, 10));

		executionCancellationService.requestCancellation(1L);

		service.convert(List.of(mediaId), ConversionOptions.defaults(), execution, owning());

		verify(conversionExecutionRecorder).finish(eq(execution), any(), any(), eq(true));
	}

	/**
	 * Being registered means a stop asked for by execution id has to be obeyed:
	 * acknowledging the request and then converting the rest of the batch anyway
	 * would be worse than never accepting it.
	 */
	@Test
	void stopsBeforeTheNextFileWhenTheCancellationComesByExecutionId(@TempDir Path tmp) throws Exception {
		Path first = Files.writeString(tmp.resolve("first.mp4"), "0123456789");

		CatalogFile other = file(Files.writeString(tmp.resolve("second.mp4"), "0123456789"), 10L);

		when(execution.getId()).thenReturn(4242L);
		when(catalogFileRepository.findByPublicIdIn(any())).thenReturn(List.of(file(first, 10L), other));

		stubSource("h264", 120.0);

		when(videoTranscoder.transcode(any(), any(), any())).thenAnswer(_ -> {
			executionCancellationService.requestCancellation(4242L);

			return TranscodeResult.failed(ConversionFailure.CANCELLED, false, false, false, 10);
		});

		ConversionResult result = service.convert(List.of(mediaId, UUID.randomUUID()), ConversionOptions.defaults(),
				execution, owning());

		Assertions.assertThat(result.items()).singleElement().extracting(ConversionFileResult::outcome)
				.isEqualTo(ConversionOutcome.CANCELLED);

		verify(videoTranscoder).transcode(any(), any(), any());
		verify(conversionExecutionRecorder).finish(eq(execution), any(), any(), eq(true));
	}

	/**
	 * A batch that dies on the way must not leave the row open: an execution still
	 * holding a null {@code finishedAt} is read everywhere as the operation
	 * currently running, and a conversion is the longest of them.
	 */
	@Test
	void aCrashMidBatchStillClosesTheExecution(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "0123456789");

		stubFile(source, 10L);
		stubSource("h264", 120.0);

		when(videoTranscoder.transcode(any(), any(), any())).thenThrow(new IllegalStateException("encoder vanished"));

		List<UUID> selection = List.of(mediaId);

		ConversionOptions options = ConversionOptions.defaults();

		ExecutionOwnership ownership = owning();

		Assertions.assertThatThrownBy(() -> service.convert(selection, options, execution, ownership))
				.isInstanceOf(IllegalStateException.class);

		verify(conversionExecutionRecorder).fail(eq(execution), any());
		verify(conversionExecutionRecorder, never()).finish(any(), any(), any(), anyBoolean());
	}

	/**
	 * An ownership that says the locks are still held: what happens when they are
	 * not is the commit service's test, not this one's.
	 */
	private ExecutionOwnership owning() {
		return mock(ExecutionOwnership.class);
	}

	private void stubFile(Path source, long sizeBytes) {
		when(catalogFileRepository.findByPublicIdIn(any())).thenReturn(List.of(file(source, sizeBytes)));
	}

	private CatalogFile file(Path source, long sizeBytes) {
		String fileName = source.getFileName().toString();

		return CatalogFile.builder().id(7L).publicId(mediaId).fileKey(source.toString()).fileName(fileName)
				.extension(fileName.substring(fileName.lastIndexOf('.') + 1)).sizeBytes(sizeBytes)
				.fileType(FileType.VIDEO).lifecycleStatus(LifecycleStatus.ACTIVE).build();
	}

	private void stubSource(String codec, Double durationSeconds) {
		when(conversionCandidateRepository.findSourcesByPublicIdIn(any()))
				.thenReturn(List.of(new ConversionSource(mediaId, codec, durationSeconds, null, null)));
	}

	/**
	 * The date the source already had travels to the commit, so a video with no
	 * embedded date is not re-dated to the instant it was converted.
	 */
	@Test
	void handsTheDateOfTheSourceToTheCommit(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "0123456789");
		Path converted = Files.writeString(tmp.resolve("clip (H.265).mp4"), "0123");

		LocalDateTime captureDate = LocalDateTime.of(2011, Month.MARCH, 4, 18, 20);

		stubFile(source, 10L);

		when(conversionCandidateRepository.findSourcesByPublicIdIn(any())).thenReturn(
				List.of(new ConversionSource(mediaId, "h264", 120.0, captureDate, DateSource.FILE_MODIFIED_AT)));
		when(videoTranscoder.transcode(any(), any(), any()))
				.thenReturn(TranscodeResult.converted(converted, false, false, false, 4_000));
		when(conversionCommitService.commit(any(), any(), eq(converted), isNull(), any(), any(), any()))
				.thenReturn(CommitResult.committed(converted, false));

		service.convert(List.of(mediaId), ConversionOptions.defaults(), execution, owning());

		verify(conversionCommitService).commit(any(), any(), eq(converted), isNull(), any(),
				eq(new ResolvedMediaDate(captureDate, DateSource.FILE_MODIFIED_AT)), any());
	}
}