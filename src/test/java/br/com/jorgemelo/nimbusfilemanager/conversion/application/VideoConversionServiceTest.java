package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLock;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
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

	private final VideoConversionService service = new VideoConversionService(catalogFileRepository,
			conversionCandidateRepository, videoTranscoder, conversionCommitService, conversionExecutionRecorder,
			operationLockService);

	private final Execution execution = mock(Execution.class);
	private final UUID mediaId = UUID.randomUUID();

	VideoConversionServiceTest() {
		when(operationLockService.acquire(eq(ExecutionType.CONVERSION), any(Path[].class))).thenReturn(operationLock);
		when(conversionExecutionRecorder.start(any(), anyInt())).thenReturn(execution);
	}

	@Test
	void reportsNothingSelectedWithoutStartingAnExecution() {
		ConversionResult result = service.convert(List.of(), ConversionOptions.defaults(), progress(), notCancelled());

		Assertions.assertThat(result.configured()).isTrue();
		Assertions.assertThat(result.total()).isZero();

		verify(conversionExecutionRecorder, never()).start(any(), anyInt());
	}

	@Test
	void refusesToQuarantineOriginalsWhileNoQuarantineFolderIsConfigured() {
		when(conversionCommitService.quarantineRoot()).thenReturn(Optional.empty());

		ConversionResult result = service.convert(List.of(mediaId),
				new ConversionOptions(ConversionQuality.BALANCED, AudioHandling.AUTO, OriginalDisposition.QUARANTINE,
						"", NameAffixPosition.SUFFIX),
				progress(), notCancelled());

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
				.thenReturn(TranscodeResult.converted(converted, false, false, 4_000));
		when(conversionCommitService.commit(any(), any(), eq(converted), isNull(), any()))
				.thenReturn(CommitResult.committed(converted, false));

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), progress(),
				notCancelled());

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
				.thenReturn(TranscodeResult.failed(ConversionFailure.ENCODER_FAILED, false, false, 1_000));

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), progress(),
				notCancelled());

		Assertions.assertThat(result.errors()).isEqualTo(1);
		Assertions.assertThat(result.converted()).isZero();
		Assertions.assertThat(source).exists();
		Assertions.assertThat(result.items().getFirst().message()).contains("O original foi mantido");

		verify(conversionCommitService, never()).commit(any(), any(), any(), any(), any());
	}

	@Test
	void countsAFailedPlacementAsAnErrorRatherThanAConversion(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "0123456789");
		Path converted = Files.writeString(tmp.resolve("clip (H.265).mp4"), "0123");

		stubFile(source, 10L);
		stubSource("h264", 120.0);

		when(videoTranscoder.transcode(any(), any(), any()))
				.thenReturn(TranscodeResult.converted(converted, false, false, 4_000));
		when(conversionCommitService.commit(any(), any(), any(), any(), any()))
				.thenReturn(CommitResult.failed(ConversionFailure.PLACEMENT_FAILED));

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), progress(),
				notCancelled());

		Assertions.assertThat(result.errors()).isEqualTo(1);
		Assertions.assertThat(result.converted()).isZero();
	}

	@Test
	void skipsAVideoThatIsAlreadyAnHevcMp4WithoutEncodingAnything(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "data");

		stubFile(source, 4L);
		stubSource("hevc", 120.0);

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), progress(),
				notCancelled());

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
				.thenReturn(TranscodeResult.converted(converted, false, false, 5));
		when(conversionCommitService.commit(any(), any(), any(), any(), any()))
				.thenReturn(CommitResult.committed(converted, false));

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), progress(),
				notCancelled());

		Assertions.assertThat(result.converted()).isEqualTo(1);

		// The video stream is copied, not re-encoded: only the container changes.
		verify(videoTranscoder).transcode(argThat(TranscodeRequest::sourceIsHevc), any(), any());
	}

	@Test
	void skipsAFileThatIsNoLongerOnDisk(@TempDir Path tmp) {
		stubFile(tmp.resolve("gone.mp4"), 10L);
		stubSource("h264", 120.0);

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), progress(),
				notCancelled());

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
				progress(), notCancelled());

		Assertions.assertThat(result.skipped()).isEqualTo(2);

		verify(videoTranscoder, never()).transcode(any(), any(), any());
	}

	@Test
	void skipsAShortcutInsteadOfConvertingWhatItPointsAt(@TempDir Path tmp) throws Exception {
		Path shortcut = Files.writeString(tmp.resolve("clip.lnk"), "shortcut");

		stubFile(shortcut, 10L);
		stubSource("h264", 120.0);

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), progress(),
				notCancelled());

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
				.thenReturn(TranscodeResult.converted(converted, false, false, 100));
		when(conversionCommitService.commit(any(), any(), any(), any(), any()))
				.thenReturn(CommitResult.committed(converted, false));

		ConversionResult result = service.convert(List.of(mediaId, UUID.randomUUID()), ConversionOptions.defaults(),
				progress(), notCancelled());

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
				.thenReturn(TranscodeResult.failed(ConversionFailure.ENCODER_FAILED, false, false, 1));

		service.convert(List.of(mediaId),
				new ConversionOptions(ConversionQuality.BALANCED, AudioHandling.AUTO, OriginalDisposition.QUARANTINE,
						"", NameAffixPosition.SUFFIX),
				progress(), notCancelled());

		ArgumentCaptor<Path[]> locked = ArgumentCaptor.forClass(Path[].class);

		verify(operationLockService).acquire(eq(ExecutionType.CONVERSION), locked.capture());

		Assertions.assertThat(locked.getValue()).containsExactlyInAnyOrder(quarantineRoot, source);
	}

	@Test
	void refusesToRunWhenAnotherOperationHoldsThePaths(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "data");

		stubFile(source, 4L);

		when(operationLockService.acquire(eq(ExecutionType.CONVERSION), any(Path[].class)))
				.thenThrow(new OperationLockException("busy"));

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), progress(),
				notCancelled());

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

			return TranscodeResult.converted(converted, false, false, 100);
		});
		when(conversionCommitService.commit(any(), any(), any(), any(), any()))
				.thenReturn(CommitResult.committed(converted, false));

		List<String> reported = new ArrayList<>();

		service.convert(List.of(mediaId), ConversionOptions.defaults(),
				(processed, total, filePercent, _) -> reported.add(processed + "/" + total + ":" + filePercent),
				notCancelled());

		Assertions.assertThat(reported).contains("0/1:40", "1/1:100");
	}

	@Test
	void recordsTheExecutionTotalsWhenTheBatchEnds(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "0123456789");
		Path converted = Files.writeString(tmp.resolve("clip (H.265).mp4"), "0123");

		stubFile(source, 10L);
		stubSource("h264", 120.0);

		when(videoTranscoder.transcode(any(), any(), any()))
				.thenReturn(TranscodeResult.converted(converted, true, false, 100));
		when(conversionCommitService.commit(any(), any(), any(), any(), any()))
				.thenReturn(CommitResult.committed(converted, true));

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), progress(),
				notCancelled());

		ArgumentCaptor<ConversionTotals> totals = ArgumentCaptor.forClass(ConversionTotals.class);

		verify(conversionExecutionRecorder).finish(eq(execution), totals.capture(), any(), anyBoolean());

		Assertions.assertThat(totals.getValue().converted()).isEqualTo(1);
		Assertions.assertThat(totals.getValue().savedBytes()).isEqualTo(6);
		Assertions.assertThat(result.items().getFirst().audioFallback()).isTrue();
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
				.thenReturn(TranscodeResult.converted(converted, false, false, 100));
		when(conversionCommitService.commit(any(), any(), any(), any(), any()))
				.thenReturn(CommitResult.partial(converted, false, ConversionFailure.QUARANTINE_FAILED));

		ConversionResult result = service.convert(List.of(mediaId),
				new ConversionOptions(ConversionQuality.BALANCED, AudioHandling.AUTO, OriginalDisposition.QUARANTINE,
						"", NameAffixPosition.SUFFIX),
				progress(), notCancelled());

		Assertions.assertThat(result.converted()).isEqualTo(1);
		Assertions.assertThat(result.items().getFirst().message()).contains("quarentena");
	}

	@Test
	void fallsBackToTheRecommendedOptionsWhenNoneAreGiven(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "data");

		stubFile(source, 4L);
		stubSource("h264", 120.0);

		when(videoTranscoder.transcode(any(), any(), any()))
				.thenReturn(TranscodeResult.failed(ConversionFailure.ENCODER_FAILED, false, false, 1));

		service.convert(List.of(mediaId), null, progress(), notCancelled());

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
				.thenReturn(TranscodeResult.failed(failure, false, false, 1));

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), progress(),
				notCancelled());

		Assertions.assertThat(result.items().getFirst().message()).isNotBlank().doesNotContain("backend.conversion");
	}

	@Test
	void convertsAVideoWhoseCodecWasNeverExtracted(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "0123456789");
		Path converted = Files.writeString(tmp.resolve("clip (H.265).mp4"), "0123");

		stubFile(source, 10L);
		stubSource(null, null);

		when(videoTranscoder.transcode(any(), any(), any()))
				.thenReturn(TranscodeResult.converted(converted, false, false, 100));
		when(conversionCommitService.commit(any(), any(), any(), any(), any()))
				.thenReturn(CommitResult.committed(converted, false));

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), progress(),
				notCancelled());

		Assertions.assertThat(result.converted()).isEqualTo(1);
	}

	@Test
	void convertsAFileTheCatalogHasNoStreamFactsFor(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "0123456789");
		Path converted = Files.writeString(tmp.resolve("clip (H.265).mp4"), "0123");

		stubFile(source, 10L);

		when(conversionCandidateRepository.findSourcesByPublicIdIn(any())).thenReturn(List.of());
		when(videoTranscoder.transcode(any(), any(), any()))
				.thenReturn(TranscodeResult.converted(converted, false, false, 100));
		when(conversionCommitService.commit(any(), any(), any(), any(), any()))
				.thenReturn(CommitResult.committed(converted, false));

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), progress(),
				notCancelled());

		Assertions.assertThat(result.converted()).isEqualTo(1);
	}

	@Test
	void reportsNoSavingWhenTheConvertedFileCanNoLongerBeMeasured(@TempDir Path tmp) throws Exception {
		Path source = Files.writeString(tmp.resolve("clip.mp4"), "0123456789");
		Path converted = tmp.resolve("vanished.mp4");

		stubFile(source, 10L);
		stubSource("h264", 120.0);

		when(videoTranscoder.transcode(any(), any(), any()))
				.thenReturn(TranscodeResult.converted(converted, false, false, 100));
		when(conversionCommitService.commit(any(), any(), any(), any(), any()))
				.thenReturn(CommitResult.committed(converted, false));

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), progress(),
				notCancelled());

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
				.thenReturn(TranscodeResult.converted(converted, false, false, 100));
		when(conversionCommitService.commit(any(), any(), any(), any(), any()))
				.thenReturn(CommitResult.committed(converted, false));

		ConversionResult result = service.convert(List.of(mediaId), ConversionOptions.defaults(), progress(),
				notCancelled());

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
		AtomicBoolean cancelled = new AtomicBoolean();

		when(videoTranscoder.transcode(any(), any(), any())).thenAnswer(_ -> {
			cancelled.set(true);

			return TranscodeResult.failed(ConversionFailure.CANCELLED, false, false, 10);
		});

		ConversionResult result = service.convert(List.of(mediaId, UUID.randomUUID()), ConversionOptions.defaults(),
				progress(), cancelled::get);

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
				.thenReturn(TranscodeResult.failed(ConversionFailure.CANCELLED, false, false, 10));

		service.convert(List.of(mediaId), ConversionOptions.defaults(), progress(), () -> true);

		verify(conversionExecutionRecorder).finish(eq(execution), any(), any(), eq(true));
	}

	private ConversionProgressCallback progress() {
		return (_, _, _, _) -> {
		};
	}

	private BooleanSupplier notCancelled() {
		return () -> false;
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
				.thenReturn(List.of(new ConversionSource(mediaId, codec, durationSeconds)));
	}
}