package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionCommitRequest;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.CommitResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionOptions;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.PlacedConversion;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionFailure;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OwnershipLostException;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MoveBaseline;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantineIntakeService;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.IntakeOutcome;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.PreparedMovements;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PreparedMovement;
import br.com.jorgemelo.nimbusfilemanager.shared.application.library.LibraryFileMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionMetricsContext;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ProcessingMetrics;

class ConversionCommitServiceTest {

	/** This test's own accumulator: nothing here is shared with another run. */
	private final ProcessingMetrics metrics = new ExecutionMetricsContext().processing();

	/**
	 * A real directory, because a prepared movement carries paths that other
	 * capabilities normalise and act on - a relative one would resolve against the
	 * project itself.
	 */
	@TempDir
	Path tempDir;

	private final ConversionFilePlacement conversionFilePlacement = mock(ConversionFilePlacement.class);
	private final QuarantineIntakeService quarantineIntakeService = mock(QuarantineIntakeService.class);
	private final ConversionCatalogService conversionCatalogService = mock(ConversionCatalogService.class);
	private final ConversionFileNaming conversionFileNaming = mock(ConversionFileNaming.class);
	private final LibraryFileMutations libraryFileMutations = mock(LibraryFileMutations.class);

	private final ConversionCommitService service = new ConversionCommitService(conversionFilePlacement,
			quarantineIntakeService, conversionCatalogService, conversionFileNaming, libraryFileMutations);

	private final ConversionOptions options = ConversionOptions.defaults();

	private final Execution execution = mock(Execution.class);
	private final Path source = Path.of("D:", "library", "clip.mp4");
	private final Path converted = Path.of("D:", "workspace", "conversion", "clip.mp4");
	private final Path placed = Path.of("D:", "library", "clip (H.265).mp4");
	private final Path quarantineRoot = Path.of("D:", "trash");

	/**
	 * Catalogued and placed, because the commit reads where the original is to
	 * decide what happens to it - and where a file is stopped being a column on
	 * the file.
	 */
	private final CatalogFile file = CatalogFiles.at(7L, source);

	ConversionCommitServiceTest() {
		// No affix by default, which is the case where the converted file inherits the
		// original's name once the original leaves the folder.
		when(conversionFileNaming.affix(any())).thenReturn("");
	}

	@Test
	void placesTheConvertedFileAndCatalogsItWhenTheOriginalStays() throws Exception {
		when(conversionFilePlacement.place(converted, source, options, execution.getId())).thenReturn(placement(placed));

		CommitResult result = service.commit(
				new ConversionCommitRequest(execution, file, converted, null, options, null), owning(), metrics);

		Assertions.assertThat(result.successful()).isTrue();
		Assertions.assertThat(result.converted()).isEqualTo(placed);
		Assertions.assertThat(result.originalQuarantined()).isFalse();
		Assertions.assertThat(result.failure()).isNull();

		verify(conversionCatalogService).catalog(placement(placed), null, metrics);

		verify(quarantineIntakeService, never()).intake(any(), any(), any(), any(), any());
	}

	@Test
	void quarantinesTheOriginalOnlyAfterTheConvertedFileIsInPlaceAndThenTakesItsName() throws Exception {
		Path renamed = Path.of("D:", "library", "clip.mp4");

		when(conversionFilePlacement.place(converted, source, options, execution.getId())).thenReturn(placement(placed));
		// The movement is minted inside the commit, so a stub cannot name the instance:
		// what it answers about is the intake being asked at all.
		when(quarantineIntakeService.intake(eq(execution), eq(file), eq(quarantineRoot), any(), any()))
				.thenReturn(IntakeOutcome.MOVED);
		when(conversionFilePlacement.renameToOriginalName(eq(placement(placed)), eq(source), any()))
				.thenReturn(placement(renamed));

		CommitResult result = service.commit(
				new ConversionCommitRequest(execution, file, converted, quarantineRoot, options, null), owning(),
				metrics);

		Assertions.assertThat(result.successful()).isTrue();
		Assertions.assertThat(result.converted()).isEqualTo(renamed);
		Assertions.assertThat(result.originalQuarantined()).isTrue();

		verify(conversionCatalogService).catalog(placement(renamed), null, metrics);
	}

	@Test
	void keepsTheAffixedNameInsteadOfInheritingTheOriginalOne() throws Exception {
		when(conversionFileNaming.affix(any())).thenReturn("_H265");
		when(conversionFilePlacement.place(converted, source, options, execution.getId())).thenReturn(placement(placed));
		when(quarantineIntakeService.intake(any(), any(), any(), any(), any())).thenReturn(IntakeOutcome.MOVED);

		CommitResult result = service.commit(
				new ConversionCommitRequest(execution, file, converted, quarantineRoot, options, null), owning(),
				metrics);

		// The user asked for that name; taking the original's would throw it away.
		Assertions.assertThat(result.converted()).isEqualTo(placed);

		verify(conversionFilePlacement, never()).renameToOriginalName(any(), any(), any());
	}

	@Test
	void keepsTheConversionWhenTheOriginalCannotBeQuarantined() throws Exception {
		when(conversionFilePlacement.place(converted, source, options, execution.getId())).thenReturn(placement(placed));
		when(quarantineIntakeService.intake(any(), any(), any(), any(), any())).thenReturn(IntakeOutcome.ERROR);

		CommitResult result = service.commit(
				new ConversionCommitRequest(execution, file, converted, quarantineRoot, options, null), owning(),
				metrics);

		Assertions.assertThat(result.successful()).isTrue();
		Assertions.assertThat(result.originalQuarantined()).isFalse();
		Assertions.assertThat(result.failure()).isEqualTo(ConversionFailure.QUARANTINE_FAILED);

		verify(conversionFilePlacement, never()).renameToOriginalName(any(), any(), any());
	}

	/**
	 * What happens to the original is an operation, reserved before anything
	 * moves, and this run is a retry of one that already carried it out.
	 *
	 * <p>
	 * Conversion owns no recovery of its own here: it hands the intake the very
	 * operation it reserved and abides by the answer. A settled operation is
	 * refused, and refusing is what keeps the converted file from claiming the
	 * original's name over a file the first attempt already dealt with.
	 */
	@Test
	void handsTheIntakeTheOperationItReservedAndAbidesByARefusal() throws Exception {
		PreparedMovement settled = PreparedMovements.settled(1L, file.getId(), tempDir.resolve("clip.mp4"),
				tempDir.resolve("trash").resolve("7__clip.mp4"));

		when(conversionFilePlacement.place(converted, source, options, execution.getId()))
				.thenReturn(placement(placed));
		when(quarantineIntakeService.prepare(any(), any(), any(), any()))
				.thenReturn(Map.of(file.getId(), settled));
		when(quarantineIntakeService.intake(any(), any(), any(), any(), any())).thenReturn(IntakeOutcome.SKIPPED);

		CommitResult result = service.commit(
				new ConversionCommitRequest(execution, file, converted, quarantineRoot, options, null), owning(),
				metrics);

		Assertions.assertThat(result.originalQuarantined()).isFalse();
		Assertions.assertThat(result.failure()).isEqualTo(ConversionFailure.QUARANTINE_FAILED);

		Long executionId = execution.getId();

		// The same operation, not a second one minted for the retry.
		verify(quarantineIntakeService).intake(execution, file, quarantineRoot, settled, executionId);
		verify(conversionFilePlacement, never()).renameToOriginalName(any(), any(), any());
	}

	@Test
	void neverTouchesTheOriginalWhenTheConvertedFileCannotBePlaced() throws Exception {
		when(conversionFilePlacement.place(converted, source, options,
				execution.getId())).thenThrow(new IOException("disk full"));

		CommitResult result = service.commit(
				new ConversionCommitRequest(execution, file, converted, quarantineRoot, options, null), owning(),
				metrics);

		Assertions.assertThat(result.successful()).isFalse();
		Assertions.assertThat(result.failure()).isEqualTo(ConversionFailure.PLACEMENT_FAILED);

		verify(quarantineIntakeService, never()).intake(any(), any(), any(), any(), any());
		verify(conversionCatalogService, never()).catalog(any(), any(), any());
		verify(conversionFileNaming).discard(converted);
	}

	@Test
	void reportsAFailedCatalogWriteWithoutUndoingTheConversion() throws Exception {
		when(conversionFilePlacement.place(converted, source, options, execution.getId())).thenReturn(placement(placed));
		doThrow(new IllegalStateException("db down")).when(conversionCatalogService).catalog(placement(placed), null,
				metrics);

		CommitResult result = service.commit(
				new ConversionCommitRequest(execution, file, converted, null, options, null), owning(), metrics);

		Assertions.assertThat(result.successful()).isTrue();
		Assertions.assertThat(result.converted()).isEqualTo(placed);
		Assertions.assertThat(result.failure()).isEqualTo(ConversionFailure.CATALOG_FAILED);
	}

	@Test
	void leavesNothingBehindWhenThePlacementItselfFailed() throws Exception {
		when(conversionFilePlacement.place(converted, source, options,
				execution.getId())).thenThrow(new IOException("disk full"));

		service.commit(new ConversionCommitRequest(execution, file, converted, null, options, null), owning(), metrics);

		// The temporary file is the only thing that existed, and it goes with the
		// failure - a successful placement renames it, so there is nothing left to
		// clean in that case.
		verify(conversionFileNaming).discard(converted);
	}

	@Test
	void exposesTheConfiguredQuarantineRoot() {
		when(quarantineIntakeService.root()).thenReturn(Optional.of(quarantineRoot));

		Assertions.assertThat(service.quarantineRoot()).contains(quarantineRoot);
	}

	@Test
	void skipsTheRenameWhenTheQuarantineIntakeOnlySkippedTheFile() throws Exception {
		when(conversionFilePlacement.place(converted, source, options, execution.getId())).thenReturn(placement(placed));
		when(quarantineIntakeService.intake(any(), any(), any(), any(), any()))
				.thenReturn(IntakeOutcome.SKIPPED);

		CommitResult result = service.commit(
				new ConversionCommitRequest(execution, file, converted, quarantineRoot, options, null), owning(),
				metrics);

		Assertions.assertThat(result.failure()).isEqualTo(ConversionFailure.QUARANTINE_FAILED);

		verify(conversionCatalogService).catalog(placement(placed), null, metrics);
	}

	/**
	 * The converted file is written now, so without carrying the original's
	 * modified time a video with no embedded or name date would be dated by the
	 * conversion instant - the capture date falls back to the oldest filesystem
	 * timestamp.
	 */
	@Test
	void theConvertedFileInheritsTheModifiedTimeOfTheOriginal(@TempDir Path tmp) throws Exception {
		Path original = Files.writeString(tmp.resolve("old.mp4"), "original");
		Path output = Files.writeString(tmp.resolve("new.mp4"), "converted");

		FileTime old = FileTime.from(Instant.parse("2011-03-04T18:20:00Z"));

		Files.setLastModifiedTime(original, old);

		CatalogFile catalogFile = CatalogFiles.at(9L, original);

		when(conversionFilePlacement.place(output, original, options, execution.getId())).thenReturn(placement(output));

		service.commit(new ConversionCommitRequest(execution, catalogFile, output, null, options, null), owning(),
				metrics);

		// The stamp itself is the port's job, and is asserted there against a real
		// file. What belongs here is that the commit asks for it, with the time the
		// original carried - the value that keeps decade-old footage off today's
		// timeline.
		verify(libraryFileMutations).carryModifiedTime(output, old, execution.getId());
	}

	/** A source that is already gone must not stop the conversion from landing. */
	@Test
	void aMissingOriginalDoesNotBreakTheCommit(@TempDir Path tmp) throws Exception {
		Path output = Files.writeString(tmp.resolve("new.mp4"), "converted");

		// Catalogued at a path nothing is left at: the commit still reads where the
		// original was, and that is the whole of what this case is about.
		CatalogFile catalogFile = CatalogFiles.at(9L, tmp.resolve("gone.mp4"));

		when(conversionFilePlacement.place(eq(output), any(), eq(options), any())).thenReturn(placement(output));

		Assertions
				.assertThat(service
						.commit(new ConversionCommitRequest(execution, catalogFile, output, null, options, null),
								owning(), metrics)
				.successful())
				.isTrue();
	}

	/**
	 * Stamping the date is a courtesy, never a condition: a file that is no longer
	 * where the placement said it is must not turn a finished conversion into a
	 * failure.
	 */
	@Test
	void aFailureToStampTheDateDoesNotFailTheConversion(@TempDir Path tmp) throws Exception {
		Path original = Files.writeString(tmp.resolve("old.mp4"), "original");
		Path vanished = tmp.resolve("vanished.mp4");

		CatalogFile catalogFile = CatalogFiles.at(9L, original);

		when(conversionFilePlacement.place(any(), eq(original), eq(options), any())).thenReturn(placement(vanished));

		Assertions
				.assertThat(service
						.commit(new ConversionCommitRequest(execution, catalogFile, converted, null, options, null),
								owning(), metrics)
				.successful())
				.isTrue();
	}
	/**
	 * The one checkpoint a conversion has, and the reason it is here rather than
	 * anywhere earlier: the encode has already finished into the workspace and cost
	 * whatever it cost. What must not happen is that result entering the library
	 * from a process that no longer holds the paths - so the guard closes the
	 * commit, not the computing.
	 */
	@Test
	void placesNothingInTheLibraryWhenTheLocksAreGone() throws Exception {
		ExecutionOwnership lost = mock(ExecutionOwnership.class);

		doThrow(new OwnershipLostException("the session that held the locks is gone")).when(lost)
				.assertMayGoOnWorking();

		ConversionCommitRequest request = new ConversionCommitRequest(execution, file, converted, null, options,
				null);

		Assertions.assertThatThrownBy(() -> service.commit(request, lost, metrics))
				.isInstanceOf(OwnershipLostException.class);

		verify(conversionFilePlacement, never()).place(any(), any(), any(), any());
	}

	/** And the encode it was about is thrown away rather than left behind. */
	@Test
	void discardsTheEncodeItCannotCommit() {
		ExecutionOwnership lost = mock(ExecutionOwnership.class);

		doThrow(new OwnershipLostException("gone")).when(lost).assertMayGoOnWorking();

		ConversionCommitRequest request = new ConversionCommitRequest(execution, file, converted, null, options,
				null);

		Assertions.assertThatThrownBy(() -> service.commit(request, lost, metrics))
				.isInstanceOf(OwnershipLostException.class);

		verify(conversionFileNaming).discard(converted);
	}

	/**
	 * An ownership that says the locks are still held, which is the ordinary case:
	 * losing them has a test of its own.
	 */
	private ExecutionOwnership owning() {
		return mock(ExecutionOwnership.class);
	}

	/**
	 * The encode where the library will keep it, carrying the digest the verified
	 * move proved of it. Never a null baseline: the placement reads the whole file
	 * twice to establish it, and the catalog is entitled to that answer rather
	 * than to a third reading of a multi-gigabyte encode.
	 */
	private static PlacedConversion placement(Path path) {
		return new PlacedConversion(path, new MoveBaseline(1024L, "digest-proved-by-the-move"));
	}
}