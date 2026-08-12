package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.GeoDatasetProgress;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LeveledBoundaryFile;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.AdminBoundaryKind;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoAdminBoundaryRepository;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;

/**
 * The seventh of the nine stages, and the three ways it can have nothing to do.
 *
 * <p>
 * What every case here holds in common is the stage itself: whether it fetched a
 * hundred territories or none, it named itself and it was counted. A stage that
 * quietly vanished when it had no work would make the same pipeline read as
 * eight steps on one run and nine on the next, and nobody watching could tell
 * that from a failure.
 */
class BoundaryTerritoryCompletionTest {

	private final AppSettingService appSettingService = mock(AppSettingService.class);
	private final GeoAdminBoundaryRepository repository = mock(GeoAdminBoundaryRepository.class);
	private final BoundarySource boundarySource = mock(BoundarySource.class);
	private final GeoJsonBoundaryImporter importer = mock(GeoJsonBoundaryImporter.class);
	private final GeoDatasetProgress progress = mock(GeoDatasetProgress.class);

	private final BoundaryTerritoryCompletion completion = new BoundaryTerritoryCompletion(appSettingService,
			repository, boundarySource, importer, progress);

	@TempDir
	Path geodata;

	@Test
	void turnedOffNamesTheStageAndCountsItWithoutAskingTheSource() {
		when(appSettingService.booleanValue(eq(SettingsConstants.BOUNDARY_AUTO_TERRITORIES), anyBoolean()))
				.thenReturn(false);

		Assertions.assertThat(completion.complete(geodata)).isZero();

		InOrder order = inOrder(progress);

		order.verify(progress).noTerritoriesMissing();
		order.verify(progress).stageFinished();

		verify(boundarySource, never()).fetchMissingCountries(any(), any());
		verify(importer, never()).importExtra(any(), any(), any());
	}

	/** Every ISO code already has a polygon of its own, so there is nothing to fill. */
	@Test
	void nothingMissingNamesTheStageAndCountsItWithoutAskingTheSource() {
		enabled();

		when(repository.findDistinctCountryCodes(AdminBoundaryKind.COUNTRY))
				.thenReturn(List.copyOf(CountryCodes.alpha3ToAlpha2().values()));

		Assertions.assertThat(completion.complete(geodata)).isZero();

		verify(progress).noTerritoriesMissing();
		verify(progress).stageFinished();
		verify(boundarySource, never()).fetchMissingCountries(any(), any());
	}

	/**
	 * The source has no per-territory data for what is missing. The stage still
	 * ran - it asked - and the count it adds is nothing.
	 */
	@Test
	void aSourceWithNoTerritoryFilesAddsNothingAndStillCountsTheStage() {
		enabled();

		when(repository.findDistinctCountryCodes(AdminBoundaryKind.COUNTRY)).thenReturn(List.of());
		when(boundarySource.fetchMissingCountries(any(), any())).thenReturn(List.of());

		Assertions.assertThat(completion.complete(geodata)).isZero();

		verify(progress).completingTerritories();
		verify(progress).stageFinished();
		verify(importer, never()).importExtra(any(), any(), any());
	}

	@Test
	void whatIsMissingIsFetchedImportedAndCounted() {
		enabled();

		when(repository.findDistinctCountryCodes(AdminBoundaryKind.COUNTRY)).thenReturn(List.of());
		when(boundarySource.fetchMissingCountries(any(), any()))
				.thenReturn(List.of(new LeveledBoundaryFile(AdminBoundaryKind.COUNTRY, geodata.resolve("abw.json"))));
		when(boundarySource.sourceTag()).thenReturn("geoBoundaries");
		when(boundarySource.version()).thenReturn("v1");
		when(importer.importExtra(any(), eq("geoBoundaries"), eq("v1"))).thenReturn(5L);

		Assertions.assertThat(completion.complete(geodata)).isEqualTo(5);

		InOrder order = inOrder(progress, importer);

		// Named before the work, counted after it: a stage that failed halfway would
		// leave the row saying which one it was, and not among those completed.
		order.verify(progress).completingTerritories();
		order.verify(importer).importExtra(any(), any(), any());
		order.verify(progress).stageFinished();
	}

	private void enabled() {
		when(appSettingService.booleanValue(eq(SettingsConstants.BOUNDARY_AUTO_TERRITORIES), anyBoolean()))
				.thenReturn(true);
	}
}