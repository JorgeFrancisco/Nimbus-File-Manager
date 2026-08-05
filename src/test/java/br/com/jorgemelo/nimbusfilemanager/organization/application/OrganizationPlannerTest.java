package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancelledException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnershipGuard;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.NoCancellations;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OwnershipLostException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionPossession;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueue;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.LocationOrganizationPolicy;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.MediaLocationService;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationFallbackMode;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationSubdivision;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.MediaGeoLocation;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.ResolvedPlace;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationDate;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationDestination;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPreviewRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationRuleResult;
import br.com.jorgemelo.nimbusfilemanager.organization.application.filter.OrganizationCandidateFilter;
import br.com.jorgemelo.nimbusfilemanager.organization.application.resolver.OrganizationConflictDetector;
import br.com.jorgemelo.nimbusfilemanager.organization.application.resolver.OrganizationDestinationResolver;
import br.com.jorgemelo.nimbusfilemanager.organization.application.resolver.OrganizationLayoutResolver;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationRuleReason;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationRuleType;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.OrganizationCandidateRepository;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.projection.OrganizationCandidate;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationConfidence;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

@ExtendWith(MockitoExtension.class)
class OrganizationPlannerTest {

	@Mock
	private OrganizationCandidateRepository organizationCandidateRepository;

	@Mock
	private OrganizationDestinationResolver destinationResolver;

	@Mock
	private OrganizationLayoutResolver layoutResolver;

	@Mock
	private OrganizationConflictDetector conflictDetector;

	@Mock
	private OrganizationCandidateFilter candidateFilter;

	@Mock
	private ExecutionProgressService executionProgressService;

	private final ExecutionCancellationService executionCancellationService = NoCancellations.none();

	@Test
	void previewShouldBuildPlanAndDetectConflicts() {
		Path source = Path.of("C:/input");
		Path target = Path.of("C:/organized");

		OrganizationCandidate candidate = candidate(1L, "photo.jpg", source.resolve("photo.jpg"), 100L);

		OrganizationDestination destination = destination(target.resolve("202405/09/CAMERA/IMAGENS/photo.jpg"), false);

		when(layoutResolver.normalize(OrganizationLayout.DEFAULT)).thenReturn("YEAR_MONTH/DAY/SUBCATEGORY/FILE_TYPE");
		when(organizationCandidateRepository.findCandidates(eq(PathUtils.normalize(source)), any(), any()))
				.thenReturn(new PageImpl<>(List.of(candidate)));
		when(candidateFilter.matches(eq(candidate), any(), eq(PathUtils.normalize(source)))).thenReturn(true);
		when(destinationResolver.resolve(target.toAbsolutePath().normalize(), "YEAR_MONTH/DAY/SUBCATEGORY/FILE_TYPE",
				candidate)).thenReturn(destination);
		when(conflictDetector.detect(any())).thenAnswer(invocation -> invocation.getArgument(0));

		var plan = planner().preview(request(source, target, true), Takings.owning(1L));

		Assertions.assertThat(plan.sourcePath()).isEqualTo(PathUtils.normalize(source));
		Assertions.assertThat(plan.targetPath()).isEqualTo(PathUtils.normalize(target));
		Assertions.assertThat(plan.items()).hasSize(1);
		Assertions.assertThat(plan.summary().totalFiles()).isEqualTo(1);
		Assertions.assertThat(plan.summary().plannedMoves()).isEqualTo(1);
		Assertions.assertThat(plan.summary().filesWithDate()).isEqualTo(1);
	}

	@Test
	void previewShouldSkipFilteredCandidatesAndAlreadyOrganizedItems() {
		Path source = Path.of("C:/input");
		Path target = Path.of("C:/organized");

		OrganizationCandidate filtered = candidate(1L, "filtered.jpg", source.resolve("filtered.jpg"), 100L);
		OrganizationCandidate samePath = candidate(2L, "same.jpg", source.resolve("same.jpg"), null);

		OrganizationDestination destination = destination(source.resolve("same.jpg"), true);

		when(layoutResolver.normalize(OrganizationLayout.DEFAULT)).thenReturn("layout");
		when(organizationCandidateRepository.findCandidates(eq(PathUtils.normalize(source)), any(), any()))
				.thenReturn(new PageImpl<>(List.of(filtered, samePath)));
		when(candidateFilter.matches(eq(filtered), any(), eq(PathUtils.normalize(source)))).thenReturn(false);
		when(candidateFilter.matches(eq(samePath), any(), eq(PathUtils.normalize(source)))).thenReturn(true);
		when(destinationResolver.resolve(target.toAbsolutePath().normalize(), "layout", samePath))
				.thenReturn(destination);
		when(conflictDetector.detect(List.of())).thenReturn(List.of());

		var plan = planner().preview(request(source, target, true), Takings.owning(1L));

		Assertions.assertThat(plan.items()).isEmpty();
		Assertions.assertThat(plan.summary().totalFiles()).isEqualTo(1);
		Assertions.assertThat(plan.summary().alreadyOrganized()).isEqualTo(1);
		Assertions.assertThat(plan.summary().filesWithoutDate()).isEqualTo(1);
	}

	@Test
	void previewWithProvidedExecutionShouldReportTotalAndProgressWhenProgressServiceIsPresent() {
		Path source = Path.of("C:/input");
		Path target = Path.of("C:/organized");

		ExecutionOwnership ownership = Takings.owning(77L);

		OrganizationCandidate candidate = candidate(1L, "photo.jpg", source.resolve("photo.jpg"), 100L);

		OrganizationDestination destination = destination(target.resolve("202405/09/CAMERA/IMAGENS/photo.jpg"), false);

		when(layoutResolver.normalize(OrganizationLayout.DEFAULT)).thenReturn("YEAR_MONTH/DAY/SUBCATEGORY/FILE_TYPE");
		when(organizationCandidateRepository.findCandidates(eq(PathUtils.normalize(source)), any(), any()))
				.thenReturn(new PageImpl<>(List.of(candidate)));
		when(candidateFilter.matches(eq(candidate), any(), eq(PathUtils.normalize(source)))).thenReturn(true);
		when(destinationResolver.resolve(target.toAbsolutePath().normalize(), "YEAR_MONTH/DAY/SUBCATEGORY/FILE_TYPE",
				candidate)).thenReturn(destination);
		when(conflictDetector.detect(any())).thenAnswer(invocation -> invocation.getArgument(0));

		var plan = planner().preview(request(source, target, true), ownership);

		Assertions.assertThat(plan.items()).hasSize(1);

		verify(executionProgressService, never()).updateTotal(any(), anyInt());
		verify(executionProgressService).updateProgress(eq(ownership), anyInt(), anyInt(), anyInt(), eq(0),
				anyString());
	}

	@Test
	void previewShouldIncludeSamePathItemWhenSkipAlreadyOrganizedIsFalse() {
		Path source = Path.of("C:/input");
		Path target = Path.of("C:/organized");

		OrganizationCandidate samePath = candidate(1L, "same.jpg", source.resolve("same.jpg"), null);

		OrganizationDestination destination = destination(source.resolve("same.jpg"), true);

		when(layoutResolver.normalize(OrganizationLayout.DEFAULT)).thenReturn("layout");
		when(organizationCandidateRepository.findCandidates(eq(PathUtils.normalize(source)), any(), any()))
				.thenReturn(new PageImpl<>(List.of(samePath)));
		when(candidateFilter.matches(eq(samePath), any(), eq(PathUtils.normalize(source)))).thenReturn(true);
		when(destinationResolver.resolve(target.toAbsolutePath().normalize(), "layout", samePath))
				.thenReturn(destination);
		when(conflictDetector.detect(any())).thenAnswer(invocation -> invocation.getArgument(0));

		var plan = planner().preview(request(source, target, false), Takings.owning(1L));

		Assertions.assertThat(plan.items()).hasSize(1);
		Assertions.assertThat(plan.items().get(0).samePath()).isTrue();
	}

	@Test
	void previewShouldReportProgressAtThousandItemCadenceWhenProgressServiceIsPresent() {
		Path source = Path.of("C:/input");
		Path target = Path.of("C:/organized");

		ExecutionOwnership ownership = Takings.owning(88L);

		List<OrganizationCandidate> candidates = new ArrayList<>();

		for (long i = 0; i < 1000; i++) {
			candidates.add(candidate(i, "file" + i + ".jpg", source.resolve("file" + i + ".jpg"), 100L));
		}

		OrganizationDestination destination = destination(target.resolve("202405/09/CAMERA/IMAGENS/file.jpg"), false);

		when(layoutResolver.normalize(OrganizationLayout.DEFAULT)).thenReturn("layout");
		when(organizationCandidateRepository.findCandidates(eq(PathUtils.normalize(source)), any(), any()))
				.thenReturn(new PageImpl<>(candidates));
		when(candidateFilter.matches(any(), any(), eq(PathUtils.normalize(source)))).thenReturn(true);
		when(destinationResolver.resolve(any(), eq("layout"), any())).thenReturn(destination);
		when(conflictDetector.detect(any())).thenAnswer(invocation -> invocation.getArgument(0));

		planner().preview(request(source, target, false), ownership);

		verify(executionProgressService).updateProgress(eq(ownership), eq(1), anyInt(), anyInt(), eq(0), anyString());
		verify(executionProgressService).updateProgress(eq(ownership), eq(1000), anyInt(), anyInt(), eq(0),
				anyString());
	}

	@Test
	void previewShouldStopAndMarkCancelledWhenCancellationIsRequestedMidLoop() {
		Path source = Path.of("C:/input");
		Path target = Path.of("C:/organized");

		ExecutionOwnership ownership = Takings.owning(55L);

		OrganizationCandidate first = candidate(1L, "first.jpg", source.resolve("first.jpg"), 100L);
		OrganizationCandidate second = candidate(2L, "second.jpg", source.resolve("second.jpg"), 100L);

		OrganizationDestination destination = destination(target.resolve("202405/09/CAMERA/IMAGENS/first.jpg"), false);

		when(layoutResolver.normalize(OrganizationLayout.DEFAULT)).thenReturn("layout");
		when(organizationCandidateRepository.findCandidates(eq(PathUtils.normalize(source)), any(), any()))
				.thenReturn(new PageImpl<>(List.of(first, second)));
		when(candidateFilter.matches(eq(first), any(), eq(PathUtils.normalize(source)))).thenAnswer(_ -> {
			executionCancellationService.requestCancellation(ownership.executionId());

			return true;
		});
		when(destinationResolver.resolve(target.toAbsolutePath().normalize(), "layout", first)).thenReturn(destination);

		OrganizationPlanner planner = new OrganizationPlanner(organizationCandidateRepository, destinationResolver,
				layoutResolver, conflictDetector, candidateFilter, executionProgressService,
				executionCancellationService, mock(MediaLocationService.class), new LocationOrganizationPolicy());

		var request = request(source, target, true);

		Assertions.assertThatThrownBy(() -> planner.preview(request, ownership))
				.isInstanceOf(ExecutionCancelledException.class);

		verify(candidateFilter, never()).matches(eq(second), any(), any());

		// The cancellation stays on the row - a request that survives the thread is
		// the whole point of persisting it.
		Assertions.assertThat(executionCancellationService.isCancelled(ownership.executionId())).isTrue();
	}

	/**
	 * Organizing by place: the planner has to fetch the locations of the candidates
	 * it is about to plan and hand the country/state/city segments to the resolver,
	 * or the photos land in date folders while the user asked for places.
	 */
	@Test
	void previewShouldResolveDestinationsWithLocationSegmentsWhenSubdivisionIsRequested() {
		Path source = Path.of("C:/input");
		Path target = Path.of("C:/organized");

		OrganizationCandidate candidate = candidate(1L, "photo.jpg", source.resolve("photo.jpg"), 100L);

		OrganizationDestination destination = destination(target.resolve("Brasil/RJ/photo.jpg"), false);

		MediaLocationService mediaLocationService = mock(MediaLocationService.class);

		MediaGeoLocation location = MediaGeoLocation.builder().manual(false)
				.place(ResolvedPlace.builder().countryName("Brasil").stateName("Rio de Janeiro").cityName("Niterói")
						.confidence(LocationConfidence.HIGH).build())
				.build();

		when(mediaLocationService.locationsOf(List.of(1L))).thenReturn(Map.of(1L, location));
		when(layoutResolver.normalize(OrganizationLayout.DEFAULT)).thenReturn("LOCATION");
		when(organizationCandidateRepository.findCandidates(eq(PathUtils.normalize(source)), any(), any()))
				.thenReturn(new PageImpl<>(List.of(candidate)));
		when(candidateFilter.matches(eq(candidate), any(), eq(PathUtils.normalize(source)))).thenReturn(true);
		when(destinationResolver.resolve(eq(target.toAbsolutePath().normalize()), eq("LOCATION"), eq(candidate),
				anyList())).thenReturn(destination);
		when(conflictDetector.detect(any())).thenAnswer(invocation -> invocation.getArgument(0));

		OrganizationPlanner planner = new OrganizationPlanner(organizationCandidateRepository, destinationResolver,
				layoutResolver, conflictDetector, candidateFilter, executionProgressService,
				executionCancellationService, mediaLocationService, new LocationOrganizationPolicy());

		var plan = planner.preview(new OrganizationPreviewRequest(source.toString(), target.toString(), true,
				OrganizationLayout.DEFAULT, 100, null, null, true, null, null, null, null,
				LocationSubdivision.COUNTRY_STATE_CITY, LocationConfidence.LOW, LocationFallbackMode.IGNORE),
				Takings.owning(1L));

		Assertions.assertThat(plan.items()).singleElement().satisfies(item -> {
			Assertions.assertThat(item.targetPath()).contains("Brasil");
			Assertions.assertThat(item.location()).contains("Brasil");
		});

		verify(mediaLocationService).locationsOf(List.of(1L));
		verify(destinationResolver).resolve(eq(target.toAbsolutePath().normalize()), eq("LOCATION"), eq(candidate),
				anyList());
	}

	/**
	 * The same worker, one attempt later. A plan being built by a taking the row
	 * has moved on from stops at the next candidate - the answer would be refused
	 * anyway, and the minutes spent reaching it are the point of stopping.
	 */
	@Test
	void aPlanWhoseRowWasTakenOverStopsAtTheNextCandidate() {
		Path source = Path.of("C:/input");
		Path target = Path.of("C:/organized");

		ExecutionOwnershipGuard guard = new ExecutionOwnershipGuard(mock(ExecutionQueue.class));

		ExecutionOwnership replaced = Takings.taking(99L, 1, guard);

		guard.takes(new ExecutionPossession(99L, "worker-that-came-back", 2));

		when(organizationCandidateRepository.findCandidates(eq(PathUtils.normalize(source)), any(), any()))
			.thenReturn(new PageImpl<>(List.of(candidate(1L, "photo.jpg", source.resolve("photo.jpg"), 100L))));

		OrganizationPlanner planner = planner();

		OrganizationPreviewRequest request = request(source, target, true);

		Assertions.assertThatThrownBy(() -> planner.preview(request, replaced))
			.isInstanceOf(OwnershipLostException.class).hasMessageContaining("taken over");

		verify(executionProgressService, never()).updateProgress(any(), anyInt(), anyInt(), anyInt(), anyInt(),
			anyString());
		verify(conflictDetector, never()).detect(any());
	}

	/** And the taking that holds the row plans exactly as before. */
	@Test
	void theTakingThatHoldsTheRowPlansNormally() {
		Path source = Path.of("C:/input");
		Path target = Path.of("C:/organized");

		ExecutionOwnershipGuard guard = new ExecutionOwnershipGuard(mock(ExecutionQueue.class));

		ExecutionOwnership current = Takings.taking(99L, 2, guard);

		guard.takes(new ExecutionPossession(99L, "worker-that-came-back", 2));

		OrganizationCandidate candidate = candidate(1L, "photo.jpg", source.resolve("photo.jpg"), 100L);

		when(layoutResolver.normalize(OrganizationLayout.DEFAULT)).thenReturn("layout");
		when(organizationCandidateRepository.findCandidates(eq(PathUtils.normalize(source)), any(), any()))
			.thenReturn(new PageImpl<>(List.of(candidate)));
		when(candidateFilter.matches(eq(candidate), any(), eq(PathUtils.normalize(source)))).thenReturn(true);
		when(destinationResolver.resolve(target.toAbsolutePath().normalize(), "layout", candidate))
			.thenReturn(destination(target.resolve("202405/photo.jpg"), false));
		when(conflictDetector.detect(any())).thenAnswer(invocation -> invocation.getArgument(0));

		Assertions.assertThat(planner().preview(request(source, target, true), current).items()).hasSize(1);
	}

	private OrganizationPlanner planner() {
		return new OrganizationPlanner(organizationCandidateRepository, destinationResolver, layoutResolver,
				conflictDetector, candidateFilter, executionProgressService, executionCancellationService,
				mock(MediaLocationService.class), new LocationOrganizationPolicy());
	}

	private OrganizationPreviewRequest request(Path source, Path target, boolean skipAlreadyOrganized) {
		return new OrganizationPreviewRequest(source.toString(), target.toString(), true, OrganizationLayout.DEFAULT,
				100, null, null, skipAlreadyOrganized, null, null, null, null);
	}

	private OrganizationCandidate candidate(Long id, String fileName, Path currentPath, Long sizeBytes) {
		return new OrganizationCandidate(id, fileName, "jpg", FileType.PHOTO, sizeBytes, currentPath.toString(),
				currentPath.getParent().toString(), 2024, 5, 9, "202405", LocalDateTime.of(2024, Month.MAY, 9, 10, 30),
				FileCategory.MEDIA, MediaSubcategory.CAMERA);
	}

	private OrganizationDestination destination(Path file, boolean missingDate) {
		OrganizationRuleResult ruleResult = new OrganizationRuleResult(OrganizationRuleType.CAMERA,
				OrganizationRuleReason.FILE_NAME, FileCategory.MEDIA, MediaSubcategory.CAMERA, FileType.PHOTO);

		return new OrganizationDestination(file.getParent(), file, new OrganizationDate("202405", "09", missingDate),
				ruleResult);
	}
}