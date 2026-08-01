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
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.CommitResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionOptions;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionFailure;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantineIntakeService;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.IntakeOutcome;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

class ConversionCommitServiceTest {

	private final ConversionFilePlacement conversionFilePlacement = mock(ConversionFilePlacement.class);
	private final QuarantineIntakeService quarantineIntakeService = mock(QuarantineIntakeService.class);
	private final ConversionCatalogService conversionCatalogService = mock(ConversionCatalogService.class);
	private final ConversionFileNaming conversionFileNaming = mock(ConversionFileNaming.class);
	private final ConversionCommitService service = new ConversionCommitService(conversionFilePlacement,
			quarantineIntakeService, conversionCatalogService, conversionFileNaming);

	private final ConversionOptions options = ConversionOptions.defaults();

	private final Execution execution = mock(Execution.class);
	private final Path source = Path.of("D:", "library", "clip.mp4");
	private final Path converted = Path.of("D:", "workspace", "conversion", "clip.mp4");
	private final Path placed = Path.of("D:", "library", "clip (H.265).mp4");
	private final Path quarantineRoot = Path.of("D:", "trash");

	private final CatalogFile file = CatalogFile.builder().id(7L).fileKey(source.toString()).fileName("clip.mp4")
			.build();

	ConversionCommitServiceTest() {
		// No affix by default, which is the case where the converted file inherits the
		// original's name once the original leaves the folder.
		when(conversionFileNaming.affix(any())).thenReturn("");
	}

	@Test
	void placesTheConvertedFileAndCatalogsItWhenTheOriginalStays() throws Exception {
		when(conversionFilePlacement.place(converted, source, options)).thenReturn(placed);

		CommitResult result = service.commit(execution, file, converted, null, options, null);

		Assertions.assertThat(result.successful()).isTrue();
		Assertions.assertThat(result.converted()).isEqualTo(placed);
		Assertions.assertThat(result.originalQuarantined()).isFalse();
		Assertions.assertThat(result.failure()).isNull();

		verify(conversionCatalogService).catalog(placed, null);

		verify(quarantineIntakeService, never()).intake(any(), any(), any(), any());
	}

	@Test
	void quarantinesTheOriginalOnlyAfterTheConvertedFileIsInPlaceAndThenTakesItsName() throws Exception {
		Path renamed = Path.of("D:", "library", "clip.mp4");

		when(conversionFilePlacement.place(converted, source, options)).thenReturn(placed);
		when(quarantineIntakeService.intake(execution, file, quarantineRoot, MovementReason.CONVERTED_QUARANTINED))
				.thenReturn(IntakeOutcome.MOVED);
		when(conversionFilePlacement.renameToOriginalName(placed, source)).thenReturn(renamed);

		CommitResult result = service.commit(execution, file, converted, quarantineRoot, options, null);

		Assertions.assertThat(result.successful()).isTrue();
		Assertions.assertThat(result.converted()).isEqualTo(renamed);
		Assertions.assertThat(result.originalQuarantined()).isTrue();

		verify(conversionCatalogService).catalog(renamed, null);
	}

	@Test
	void keepsTheAffixedNameInsteadOfInheritingTheOriginalOne() throws Exception {
		when(conversionFileNaming.affix(any())).thenReturn("_H265");
		when(conversionFilePlacement.place(converted, source, options)).thenReturn(placed);
		when(quarantineIntakeService.intake(any(), any(), any(), any())).thenReturn(IntakeOutcome.MOVED);

		CommitResult result = service.commit(execution, file, converted, quarantineRoot, options, null);

		// The user asked for that name; taking the original's would throw it away.
		Assertions.assertThat(result.converted()).isEqualTo(placed);

		verify(conversionFilePlacement, never()).renameToOriginalName(any(), any());
	}

	@Test
	void keepsTheConversionWhenTheOriginalCannotBeQuarantined() throws Exception {
		when(conversionFilePlacement.place(converted, source, options)).thenReturn(placed);
		when(quarantineIntakeService.intake(any(), any(), any(), any())).thenReturn(IntakeOutcome.ERROR);

		CommitResult result = service.commit(execution, file, converted, quarantineRoot, options, null);

		Assertions.assertThat(result.successful()).isTrue();
		Assertions.assertThat(result.originalQuarantined()).isFalse();
		Assertions.assertThat(result.failure()).isEqualTo(ConversionFailure.QUARANTINE_FAILED);

		verify(conversionFilePlacement, never()).renameToOriginalName(any(), any());
	}

	@Test
	void neverTouchesTheOriginalWhenTheConvertedFileCannotBePlaced() throws Exception {
		when(conversionFilePlacement.place(converted, source, options)).thenThrow(new IOException("disk full"));

		CommitResult result = service.commit(execution, file, converted, quarantineRoot, options, null);

		Assertions.assertThat(result.successful()).isFalse();
		Assertions.assertThat(result.failure()).isEqualTo(ConversionFailure.PLACEMENT_FAILED);

		verify(quarantineIntakeService, never()).intake(any(), any(), any(), any());
		verify(conversionCatalogService, never()).catalog(any(), any());
		verify(conversionFileNaming).discard(converted);
	}

	@Test
	void reportsAFailedCatalogWriteWithoutUndoingTheConversion() throws Exception {
		when(conversionFilePlacement.place(converted, source, options)).thenReturn(placed);
		doThrow(new IllegalStateException("db down")).when(conversionCatalogService).catalog(placed, null);

		CommitResult result = service.commit(execution, file, converted, null, options, null);

		Assertions.assertThat(result.successful()).isTrue();
		Assertions.assertThat(result.converted()).isEqualTo(placed);
		Assertions.assertThat(result.failure()).isEqualTo(ConversionFailure.CATALOG_FAILED);
	}

	@Test
	void leavesNothingBehindWhenThePlacementItselfFailed() throws Exception {
		when(conversionFilePlacement.place(converted, source, options)).thenThrow(new IOException("disk full"));

		service.commit(execution, file, converted, null, options, null);

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
		when(conversionFilePlacement.place(converted, source, options)).thenReturn(placed);
		when(quarantineIntakeService.intake(any(), any(), any(), eq(MovementReason.CONVERTED_QUARANTINED)))
				.thenReturn(IntakeOutcome.SKIPPED);

		CommitResult result = service.commit(execution, file, converted, quarantineRoot, options, null);

		Assertions.assertThat(result.failure()).isEqualTo(ConversionFailure.QUARANTINE_FAILED);

		verify(conversionCatalogService).catalog(placed, null);
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

		CatalogFile catalogFile = CatalogFile.builder().id(9L).fileKey(original.toString()).fileName("old.mp4").build();

		when(conversionFilePlacement.place(output, original, options)).thenReturn(output);

		service.commit(execution, catalogFile, output, null, options, null);

		Assertions.assertThat(Files.getLastModifiedTime(output)).isEqualTo(old);
	}

	/** A source that is already gone must not stop the conversion from landing. */
	@Test
	void aMissingOriginalDoesNotBreakTheCommit(@TempDir Path tmp) throws Exception {
		Path output = Files.writeString(tmp.resolve("new.mp4"), "converted");

		CatalogFile catalogFile = CatalogFile.builder().id(9L).fileKey(tmp.resolve("gone.mp4").toString())
				.fileName("gone.mp4").build();

		when(conversionFilePlacement.place(eq(output), any(), eq(options))).thenReturn(output);

		Assertions.assertThat(service.commit(execution, catalogFile, output, null, options, null).successful())
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

		CatalogFile catalogFile = CatalogFile.builder().id(9L).fileKey(original.toString()).fileName("old.mp4").build();

		when(conversionFilePlacement.place(any(), eq(original), eq(options))).thenReturn(vanished);

		Assertions.assertThat(service.commit(execution, catalogFile, converted, null, options, null).successful())
				.isTrue();
	}
}