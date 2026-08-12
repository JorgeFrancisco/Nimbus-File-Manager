package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentObservation;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentVerificationPayload;
import br.com.jorgemelo.nimbusfilemanager.catalog.domain.enums.ContentOutcome;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

/**
 * The durable reading a watcher asks for when a stat cannot settle what a file
 * holds.
 *
 * <p>
 * The whole of its difficulty is that the file is live: it is being written by
 * whatever prompted the observation, and a digest of a file that changed while
 * it was being read describes no generation that ever existed. So it stats,
 * reads, and stats again - and an answer taken across a change is thrown away
 * rather than recorded.
 */
class ContentVerificationJobHandlerTest {

	private static final Instant NOW = Instant.parse("2026-08-14T06:00:00Z");
	private static final Instant OBSERVED_AT = Instant.parse("2026-08-14T05:30:00Z");

	private final CatalogFileRepository catalogFileRepository = mock(CatalogFileRepository.class);
	private final ContentReconciliation contentReconciliation = mock(ContentReconciliation.class);
	private final ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);
	/** With the time module, as the application's own mapper has: the payload
	 * carries the moment the change was seen. */
	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(
			new ObjectMapper().findAndRegisterModules());

	@Test
	void readsTheFileAndHandsWhatItFoundToTheOneThingThatDecides(@TempDir Path folder) throws IOException {
		Path file = Files.writeString(folder.resolve("photo.jpg"), "content");

		catalogued(7L, file);

		handler(new FileHashService()).handle(execution(), claimed(payload(7L, OBSERVED_AT)), Takings.owning(1L));

		ArgumentCaptor<ContentObservation> observation = ArgumentCaptor.forClass(ContentObservation.class);

		verify(contentReconciliation).reconcile(any(), observation.capture());

		Assertions.assertThat(observation.getValue().observed().sha256()).isNotBlank();
		Assertions.assertThat(observation.getValue().observed().sizeBytes()).isEqualTo(Files.size(file));
		Assertions.assertThat(observation.getValue().source()).isEqualTo(CatalogEventSources.WATCHER);

		// The moment the change was seen, not the moment this got round to reading
		// it: the fact is about what happened, and this ran minutes later.
		Assertions.assertThat(observation.getValue().occurredAt()).isEqualTo(OBSERVED_AT);
	}

	@Test
	void anObservationThatNamedNoMomentIsDatedWhenItWasRead(@TempDir Path folder) throws IOException {
		Path file = Files.writeString(folder.resolve("photo.jpg"), "content");

		catalogued(7L, file);

		handler(new FileHashService()).handle(execution(), claimed(payload(7L, null)), Takings.owning(1L));

		ArgumentCaptor<ContentObservation> observation = ArgumentCaptor.forClass(ContentObservation.class);

		verify(contentReconciliation).reconcile(any(), observation.capture());

		Assertions.assertThat(observation.getValue().occurredAt()).isEqualTo(NOW);
	}

	/**
	 * This job reads a place on disk, so it is excluded by that place - and the
	 * dispatcher refuses, before claiming, any request of a type that needs a path
	 * lock while naming no path.
	 *
	 * <p>
	 * Stated here because it is the half of the contract the request has to be
	 * built against: a reconcile once asked for thousands of these with a null
	 * path, and every one was failed at that guard without ever running. Whoever
	 * makes this answer false is also deciding that a verification may run beside a
	 * move of the same tree.
	 */
	@Test
	void readingAPlaceOnDiskIsWorkThatHasToLockThatPlace() {
		Assertions.assertThat(handler(new FileHashService()).requiresPathLock()).isTrue();
	}

	@Test
	void aFilePurgedWhileThisWaitedToRunHasNothingToBeRightAbout() {
		when(catalogFileRepository.findById(7L)).thenReturn(Optional.empty());

		handler(new FileHashService()).handle(execution(),
				claimed(payload(7L, OBSERVED_AT)), Takings.owning(1L));

		verify(contentReconciliation, never()).reconcile(any(), any());

		finishedAs(ExecutionStatus.FINISHED);
	}

	@Test
	void aFileWithNoPlacementIsNotSomethingThisCanRead() {
		when(catalogFileRepository.findById(7L)).thenReturn(Optional.of(CatalogFile.builder().id(7L).build()));

		handler(new FileHashService()).handle(execution(),
				claimed(payload(7L, OBSERVED_AT)), Takings.owning(1L));

		verify(contentReconciliation, never()).reconcile(any(), any());
	}

	/**
	 * Whether the catalog should forget a file that left the disk is the
	 * reconciliation of presence, which is the walk's question. This one is only
	 * ever about content.
	 */
	@Test
	void aFileThatLeftTheDiskIsNotThisJobsQuestion(@TempDir Path folder) {
		catalogued(7L, folder.resolve("gone.jpg"));

		handler(new FileHashService()).handle(execution(), claimed(payload(7L, OBSERVED_AT)), Takings.owning(1L));

		verify(contentReconciliation, never()).reconcile(any(), any());

		finishedAs(ExecutionStatus.FINISHED);
	}

	/**
	 * The file was still being written. A digest taken across that describes no
	 * generation that ever existed, so it is discarded and the file read again.
	 */
	@Test
	void aFileThatChangedWhileItWasBeingReadIsReadAgain(@TempDir Path folder) throws IOException {
		Path file = Files.writeString(folder.resolve("photo.jpg"), "first");

		catalogued(7L, file);

		FileHashService writingWhileItReads = new FileHashService() {

			private boolean written;

			@Override
			public String sha256(Path path) {
				if (!written) {
					written = true;

					write(path, "the writer was not finished");
				}

				return super.sha256(path);
			}
		};

		handler(writingWhileItReads).handle(execution(), claimed(payload(7L, OBSERVED_AT)), Takings.owning(1L));

		ArgumentCaptor<ContentObservation> observation = ArgumentCaptor.forClass(ContentObservation.class);

		// Once, and describing the file as it settled - not the reading taken across
		// the write.
		verify(contentReconciliation).reconcile(any(), observation.capture());

		Assertions.assertThat(observation.getValue().observed().sizeBytes()).isEqualTo(Files.size(file));
	}

	/**
	 * A file being written continuously never settles, and there is no answer to
	 * give. Left for the next observation rather than reported as a failure: the
	 * writer is doing nothing wrong.
	 */
	@Test
	void aFileThatNeverSettlesIsLeftForTheNextObservation(@TempDir Path folder) throws IOException {
		Path file = Files.writeString(folder.resolve("photo.jpg"), "first");

		catalogued(7L, file);

		FileHashService alwaysWriting = new FileHashService() {

			private int reads;

			@Override
			public String sha256(Path path) {
				write(path, "still going " + reads++);

				return super.sha256(path);
			}
		};

		handler(alwaysWriting).handle(execution(), claimed(payload(7L, OBSERVED_AT)), Takings.owning(1L));

		verify(contentReconciliation, never()).reconcile(any(), any());

		finishedAs(ExecutionStatus.FINISHED);
	}

	/**
	 * Somebody else settled a different answer for the same generation while this
	 * was reading. Reported as an execution that ended with errors, because it is
	 * a run that could not do what it was asked.
	 */
	@Test
	void ananswerAnotherWorkerBeatUsToIsReportedAsSuch(@TempDir Path folder) throws IOException {
		Path file = Files.writeString(folder.resolve("photo.jpg"), "content");

		catalogued(7L, file);

		when(contentReconciliation.reconcile(any(), any())).thenReturn(ContentOutcome.CONFLICT);

		handler(new FileHashService()).handle(execution(), claimed(payload(7L, OBSERVED_AT)), Takings.owning(1L));

		finishedAs(ExecutionStatus.FINISHED_WITH_ERRORS);
	}

	private static void write(Path path, String content) {
		try {
			Files.writeString(path, content);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private ContentVerificationJobHandler handler(FileHashService fileHashService) {
		return new ContentVerificationJobHandler(catalogFileRepository, contentReconciliation, fileHashService,
				executionProgressService, executionPayloadCodec, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private void catalogued(long catalogFileId, Path path) {
		Optional<CatalogFile> file = Optional.of(CatalogFiles.at(catalogFileId, path));

		when(catalogFileRepository.findById(catalogFileId)).thenReturn(file);
	}

	private String payload(long catalogFileId, Instant observedAt) {
		return executionPayloadCodec.encode(new ContentVerificationPayload(
				ContentVerificationPayload.SCHEMA_VERSION, catalogFileId, observedAt));
	}

	private ClaimedExecution claimed(String payload) {
		return new ClaimedExecution(1L, ExecutionType.CONTENT_VERIFICATION.name(), null, null, payload);
	}

	private Execution execution() {
		return Execution.builder().id(1L).executionType(ExecutionType.CONTENT_VERIFICATION).build();
	}

	private void finishedAs(ExecutionStatus status) {
		verify(executionProgressService, times(1)).finishCommand(any(), eq(status), any(), any());
	}
}