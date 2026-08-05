package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.SimilarityLauncher;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DrainResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogPayload;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence.SimilarityRelationWriter;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnershipGuard;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueue;
import br.com.jorgemelo.nimbusfilemanager.shared.application.BackgroundWorkGate;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * The backlog as an intention and as a run.
 *
 * <p>
 * What the pair has to get right is that asking is cheap and repeatable while
 * doing is exclusive, and that neither side keeps anything in memory: two
 * requests to drain the same backlog are one row, and a drain that dies is the
 * same drain when it comes back, because the work is a query rather than a list.
 */
class FingerprintBacklogQueueTest {

	private final ExecutionEnqueueService executionEnqueueService = mock(ExecutionEnqueueService.class);
	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(new ObjectMapper());
	private final BackgroundWorkGate backgroundWorkGate = mock(BackgroundWorkGate.class);

	private final PhashBacklogService photoBacklog = mock(PhashBacklogService.class);
	private final VideoFingerprintBacklogService videoBacklog = mock(VideoFingerprintBacklogService.class);
	private final ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);
	private final ExecutionCancellationService executionCancellationService = mock(ExecutionCancellationService.class);

	private final FingerprintBacklogLauncher launcher = new FingerprintBacklogLauncher(photoBacklog, videoBacklog,
			executionEnqueueService, executionPayloadCodec, backgroundWorkGate);

	private final ExecutionOwnershipGuard guard = new ExecutionOwnershipGuard(mock(ExecutionQueue.class));

	private final SimilarityLauncher similarityLauncher = mock(SimilarityLauncher.class);

	private final PhotoFingerprintJobHandler photos = new PhotoFingerprintJobHandler(photoBacklog,
			similarityLauncher, executionProgressService, executionCancellationService,
			executionPayloadCodec);
	private final VideoFingerprintJobHandler videos = new VideoFingerprintJobHandler(videoBacklog,
			executionProgressService, executionCancellationService, executionPayloadCodec,
			similarityLauncher);

	@BeforeEach
	void thereIsWorkAndTheQueueAcceptsIt() {
		when(photoBacklog.status()).thenReturn(new FingerprintBacklogStatus(120, 0, 0));
		when(videoBacklog.status()).thenReturn(new FingerprintBacklogStatus(30, 0, 0));

		doAnswer(call -> {
			Execution queued = call.getArgument(0);

			queued.setStatus(ExecutionStatus.PENDING);

			return queued;
		}).when(executionEnqueueService).enqueueOrExisting(any());
	}

	/**
	 * The whole reason a backlog can be deduplicated where an organization cannot:
	 * it has no arguments. "Everything of this kind that is missing a fingerprint"
	 * asked twice is one request, because the second would compute nothing the
	 * first will not reach.
	 */
	@Test
	void twoRequestsToDrainTheSameBacklogAreOneRequest() {
		String first = capture(() -> launcher.launch(ExecutionType.FINGERPRINT_PHOTO, false)).getDedupKey();
		String again = capture(() -> launcher.launch(ExecutionType.FINGERPRINT_PHOTO, false)).getDedupKey();

		Assertions.assertThat(again).isEqualTo(first);
	}

	/**
	 * A rebuild is not the same request. "Redo everything" and "finish what is
	 * missing" are different things to ask for, and collapsing one onto the other
	 * would answer a question nobody asked.
	 */
	@Test
	void aRebuildIsADifferentRequestFromADrain() {
		String drain = capture(() -> launcher.launch(ExecutionType.FINGERPRINT_PHOTO, false)).getDedupKey();
		String rebuild = capture(() -> launcher.launch(ExecutionType.FINGERPRINT_PHOTO, true)).getDedupKey();

		Assertions.assertThat(rebuild).isNotEqualTo(drain);
	}

	/** And the two media never collapse onto each other. */
	@Test
	void photosAndVideosAreDifferentRequests() {
		String photo = capture(() -> launcher.launch(ExecutionType.FINGERPRINT_PHOTO, false)).getDedupKey();
		String video = capture(() -> launcher.launch(ExecutionType.FINGERPRINT_VIDEO, false)).getDedupKey();

		Assertions.assertThat(video).isNotEqualTo(photo);
	}

	@Test
	void bothBacklogsAreAskedForBecauseAConversionCompetesWithEither() {
		launcher.launchBoth();

		ArgumentCaptor<Execution> queued = ArgumentCaptor.forClass(Execution.class);

		verify(executionEnqueueService, times(2)).enqueueOrExisting(queued.capture());

		Assertions.assertThat(queued.getAllValues()).extracting(Execution::getExecutionType)
				.containsExactly(ExecutionType.FINGERPRINT_PHOTO, ExecutionType.FINGERPRINT_VIDEO);
	}

	/**
	 * Queueing while the application is closing writes a row nothing will claim
	 * before the pool goes. Nothing is lost - the backlog is whatever is still
	 * pending - so the ask is simply skipped.
	 */
	@Test
	void nothingIsAskedForWhileTheApplicationIsClosing() {
		when(backgroundWorkGate.standDown()).thenReturn(true);

		Assertions.assertThat(launcher.launch(ExecutionType.FINGERPRINT_PHOTO, false)).isEmpty();

		verify(executionEnqueueService, never()).enqueueOrExisting(any());
	}

	/**
	 * A row queued over an empty backlog would be claimed, find nothing and finish -
	 * a run in the history that never did anything, once per restart and once per
	 * inventory. The old runner refused for the same reason; what changed is only
	 * where the refusal happens.
	 */
	@Test
	void anEmptyBacklogIsNotWorthARow() {
		when(photoBacklog.status()).thenReturn(new FingerprintBacklogStatus(0, 900, 3));

		Assertions.assertThat(launcher.launch(ExecutionType.FINGERPRINT_PHOTO, false)).isEmpty();

		verify(executionEnqueueService, never()).enqueueOrExisting(any());
	}

	/** A rebuild is exempt: it makes the work it is asking about. */
	@Test
	void aRebuildIsAskedForEvenWithNothingPending() {
		when(photoBacklog.status()).thenReturn(new FingerprintBacklogStatus(0, 900, 0));

		Assertions.assertThat(launcher.launch(ExecutionType.FINGERPRINT_PHOTO, true)).isPresent();
	}

	/**
	 * One at a time each, and a slot each. A photo hash is CPU and a decode, a
	 * video hash is ffmpeg and several seeks: they ran side by side before and they
	 * run side by side now, neither waiting for the other and neither twice.
	 */
	@Test
	void eachMediaRunsOneAtATimeInASlotOfItsOwn() {
		Assertions.assertThat(photos.type()).isEqualTo(ExecutionType.FINGERPRINT_PHOTO);
		Assertions.assertThat(videos.type()).isEqualTo(ExecutionType.FINGERPRINT_VIDEO);

		Assertions.assertThat(photos.concurrencyLimit()).isEqualTo(1);
		Assertions.assertThat(videos.concurrencyLimit()).isEqualTo(1);
	}

	/**
	 * A backlog holds no tree. Both handlers inherit that answer from the class
	 * they extend rather than declaring it, which is exactly the shape that used to
	 * be read the wrong way round - so it is asserted of the concrete handlers, the
	 * ones the dispatcher actually asks.
	 */
	@Test
	void neitherDrainTakesAPathLock() {
		Assertions.assertThat(photos.requiresPathLock()).isFalse();
		Assertions.assertThat(videos.requiresPathLock()).isFalse();
	}

	/**
	 * Repeatable without a checkpoint, because a backlog is a query: a second
	 * attempt asks again and finds what the first did not reach.
	 */
	@Test
	void aDrainIsSafeToRunAgainFromTheStart() {
		Assertions.assertThat(photos.resumable()).isTrue();
		Assertions.assertThat(videos.resumable()).isTrue();

		whenDraining(photoBacklog, new DrainResult(4, 0));

		photos.handle(execution(), claimed(payload(1, false)), Takings.unfenced(1L));
		photos.handle(execution(), claimed(payload(1, false)), Takings.unfenced(1L));

		verify(photoBacklog, times(2)).drainPending(any(), any(), any());
		verify(photoBacklog, never()).seedRebuild(any());
	}

	@Test
	void aRebuildWritesDownWhatItOwesBeforeDraining() {
		whenDraining(photoBacklog, new DrainResult(10, 0));

		photos.handle(execution(), claimed(payload(1, true)), Takings.taking(1L, 1, guard));

		verify(photoBacklog).seedRebuild(any());
		verify(photoBacklog).drainPending(any(), any(), any());
	}

	/**
	 * The attempt number is what tells a request apart from its own continuation.
	 * A rebuild whose worker died comes back as the same row carrying the same
	 * payload, so reading the payload alone would write the whole library down
	 * again on every attempt - re-owing every file the earlier attempts had
	 * already finished.
	 */
	@Test
	void aRebuildThatCameBackFromTheQueueDoesNotWriteItDownAgain() {
		whenDraining(photoBacklog, new DrainResult(10, 0));

		photos.handle(execution(), claimed(payload(1, true)), Takings.taking(1L, 2, guard));

		verify(photoBacklog, never()).seedRebuild(any());
		verify(photoBacklog).drainPending(any(), any(), any());
	}

	/** And a second request is a row of its own, so it tops the list back up. */
	@Test
	void askingForTheRebuildAgainWritesDownWhatIsMissingFromTheList() {
		whenDraining(photoBacklog, new DrainResult(10, 0));

		photos.handle(execution(), claimed(payload(1, true)), Takings.taking(1L, 1, guard));
		photos.handle(execution(), claimed(payload(1, true)), Takings.taking(2L, 1, guard));

		verify(photoBacklog, times(2)).seedRebuild(any());
	}

	/**
	 * A refused seed stops the run where it stands: nothing was written down, so
	 * there is nothing for this run to drain on a row that belongs to somebody
	 * else. What refuses it is proved against a real database; what is proved here
	 * is that the handler believes the answer.
	 */
	@Test
	void aRefusedSeedDrainsNothing() {
		whenDraining(photoBacklog, new DrainResult(10, 0));

		when(photoBacklog.seedRebuild(any())).thenReturn(OptionalLong.empty());

		photos.handle(execution(), claimed(payload(1, true)), Takings.taking(1L, 1, guard));

		verify(photoBacklog, never()).drainPending(any(), any(), any());
	}

	/**
	 * Opening a rebuild discards nothing derived, because it discards no
	 * fingerprint either.
	 *
	 * <p>
	 * The wholesale forget was the other half of the wholesale delete: relations
	 * drawn from hashes that had just been thrown away were relations nobody could
	 * check. Nothing is thrown away now - each file's relations are forgotten in
	 * the transaction that replaces that file's fingerprint - so a seed that reached
	 * for the algorithm-wide form would delete every conclusion in the library and
	 * leave the answer on screen with nothing behind it.
	 */
	@Test
	void neitherHandlerCanReachTheWholesaleInvalidationAtAll() {
		assertThat(collaboratorsOf(PhotoFingerprintJobHandler.class))
				.as("a handler that cannot hold the relation writer cannot discard by algorithm")
				.doesNotContain(SimilarityRelationWriter.class);
		assertThat(collaboratorsOf(VideoFingerprintJobHandler.class))
				.doesNotContain(SimilarityRelationWriter.class);
	}

	private List<Class<?>> collaboratorsOf(Class<?> handler) {
		return Arrays.asList(handler.getDeclaredConstructors()[0].getParameterTypes());
	}

	/**
	 * Photos that have a fingerprint for the first time are photos no analysis has
	 * ever seen, so every answer that exists is out of date by exactly them.
	 */
	@Test
	void aDrainThatProducedFingerprintsAsksForTheArrivalsToBeIncorporated() {
		whenDraining(photoBacklog, new DrainResult(12, 0));

		photos.handle(execution(), claimed(payload(1, false)), Takings.unfenced(1L));

		verify(similarityLauncher).refreshPhotosAfterArrival();
	}

	/**
	 * A drain that wrote nothing changes nothing, and asking would queue an
	 * execution whose only outcome is to discover it has nothing to do.
	 */
	@Test
	void aDrainThatProducedNothingAsksForNothing() {
		whenDraining(photoBacklog, new DrainResult(0, 3));

		photos.handle(execution(), claimed(payload(1, false)), Takings.unfenced(1L));

		verifyNoInteractions(similarityLauncher);
	}

	/**
	 * A drain whose row was taken over asks for nothing, however much it wrote.
	 *
	 * <p>
	 * Queueing an analysis is the one thing here that outlives the run, and the
	 * taking that replaced this one will ask for its own when it finishes: two
	 * requests for the same family is work done twice for one answer. Cooperative
	 * like the rest of this layer - what it saves is a redundant execution, not a
	 * wrong one.
	 */
	@Test
	void aDrainThatLostTheRowLeavesTheAskingToWhoeverHoldsItNow() {
		whenDraining(photoBacklog, new DrainResult(12, 0));

		photos.handle(execution(), claimed(payload(1, false)), Takings.replaced(1L));

		verifyNoInteractions(similarityLauncher);
	}

	/** And videos ask on the same condition, from a handler of their own. */
	@Test
	void aVideoDrainThatLostTheRowLeavesTheAskingToWhoeverHoldsItNow() {
		whenDraining(videoBacklog, new DrainResult(12, 0));

		videos.handle(execution(), claimed(payload(1, false)), Takings.replaced(1L));

		verifyNoInteractions(similarityLauncher);
	}

	/**
	 * A drain that was cancelled half way still wrote the fingerprints it had
	 * computed, and those files have arrived as surely as the ones a complete run
	 * wrote. Refusing to incorporate them would leave the library permanently
	 * behind by whatever a cancel happened to interrupt.
	 */
	@Test
	void aCancelledDrainStillAsksForWhatItManagedToWrite() {
		whenDraining(photoBacklog, new DrainResult(5, 0));
		when(executionCancellationService.isCancelled(any())).thenReturn(true);

		photos.handle(execution(), claimed(payload(1, false)), Takings.unfenced(1L));

		verify(similarityLauncher).refreshPhotosAfterArrival();
	}

	/**
	 * An inventory is adding the very files this would hash and a conversion is
	 * using the ffmpeg it needs. Stepping aside was the behaviour before; what
	 * changed is that it now ends a row - and the inventory asks for a new one when
	 * it finishes.
	 */
	@Test
	void aDrainClaimedWhileAnInventoryIsRunningStepsAside() {
		when(photoBacklog.pausedByActiveExecution()).thenReturn(true);

		photos.handle(execution(), claimed(payload(1, false)), Takings.unfenced(1L));

		verify(photoBacklog, never()).drainPending(any(), any(), any());
		verify(photoBacklog, never()).seedRebuild(any());
		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.REJECTED), any(), any());
	}

	@Test
	void aCancelledDrainEndsCancelledAndKeepsWhatItWrote() {
		whenDraining(photoBacklog, new DrainResult(7, 0));
		when(executionCancellationService.isCancelled(any())).thenReturn(true);

		photos.handle(execution(), claimed(payload(1, false)), Takings.unfenced(1L));

		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.CANCELLED), any(), any());
	}

	@Test
	void aDrainWithFailuresSaysSoWithoutBeingAFailure() {
		whenDraining(photoBacklog, new DrainResult(90, 10));

		photos.handle(execution(), claimed(payload(1, false)), Takings.unfenced(1L));

		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.FINISHED_WITH_ERRORS), any(), any());
	}

	@Test
	void aCleanDrainFinishes() {
		whenDraining(videoBacklog, new DrainResult(12, 0));

		videos.handle(execution(), claimed(payload(1, false)), Takings.unfenced(1L));

		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.FINISHED), any(), any());
	}

	/**
	 * Progress is written to the row as it goes, which is what makes it survive the
	 * process doing the work: a screen in another JVM reads it, and so does the same
	 * screen after a restart.
	 */
	@Test
	void progressIsReportedAgainstTheRow() {
		when(photoBacklog.status()).thenReturn(new FingerprintBacklogStatus(120, 0, 0));
		when(photoBacklog.drainPending(any(), any(), any())).thenAnswer(call -> {
			ProgressListener progress = call.getArgument(1);

			progress.onProgress(30, 2);

			return new DrainResult(30, 2);
		});

		photos.handle(execution(), claimed(payload(1, false)), Takings.unfenced(1L));

		verify(executionProgressService).updateTotal(any(), eq(120));
		verify(executionProgressService).updateLiveProgress(any(), eq(120), eq(30), eq(0), eq(2), any());
	}

	/**
	 * The drain asks whether to stop while it runs, and the answer comes from the
	 * database - a cancellation the user asked for in the application, or an
	 * inventory that started meanwhile. Neither is visible as a field to the process
	 * doing the hashing.
	 */
	@Test
	void whetherToKeepGoingIsAskedOfTheDatabaseDuringTheDrain() {
		List<Boolean> answers = new ArrayList<>();

		when(photoBacklog.status()).thenReturn(new FingerprintBacklogStatus(10, 0, 0));
		when(photoBacklog.drainPending(any(), any(), any())).thenAnswer(call -> {
			BooleanSupplier stop = call.getArgument(0);

			answers.add(stop.getAsBoolean());

			when(photoBacklog.pausedByActiveExecution()).thenReturn(true);

			answers.add(stop.getAsBoolean());

			return new DrainResult(4, 0);
		});

		photos.handle(execution(), claimed(payload(1, false)), Takings.unfenced(1L));

		Assertions.assertThat(answers).containsExactly(false, true);
	}

	@Test
	void aPayloadFromAnotherSchemaIsRefusedBeforeAnythingIsDiscarded() {
		Execution execution = execution();
		ClaimedExecution claimed = claimed(payload(99, true));

		Assertions.assertThatThrownBy(() -> photos.handle(execution, claimed, null))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schema");

		verify(photoBacklog, never()).seedRebuild(any());
		verify(photoBacklog, never()).drainPending(any(), any(), any());
	}

	private void whenDraining(FingerprintBacklog backlog, DrainResult result) {
		when(backlog.status()).thenReturn(new FingerprintBacklogStatus(result.processed() + result.failed(), 0, 0));
		when(backlog.drainPending(any(), any(), any())).thenReturn(result);
		// A rebuild that was asked for is a rebuild that ran: what a refused one does
		// is the subject of the fencing tests, against a real database.
		lenient().when(backlog.seedRebuild(any())).thenReturn(OptionalLong.of(result.processed()));
	}

	private Execution capture(Runnable launch) {
		launch.run();

		ArgumentCaptor<Execution> queued = ArgumentCaptor.forClass(Execution.class);

		verify(executionEnqueueService, atLeastOnce()).enqueueOrExisting(queued.capture());

		return queued.getValue();
	}

	private Execution execution() {
		return Execution.builder().id(42L).executionType(ExecutionType.FINGERPRINT_PHOTO).build();
	}

	private String payload(int schemaVersion, boolean rebuild) {
		return executionPayloadCodec.encode(new FingerprintBacklogPayload(schemaVersion, rebuild));
	}

	private ClaimedExecution claimed(String payload) {
		return new ClaimedExecution(42L, ExecutionType.FINGERPRINT_PHOTO.name(), null, null, payload);
	}
}