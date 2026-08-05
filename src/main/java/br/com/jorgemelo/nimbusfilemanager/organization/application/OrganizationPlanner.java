package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancelledException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OwnershipLostException;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.LocationOrganizationPolicy;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.MediaLocationService;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationSubdivision;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.MediaGeoLocation;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationDestination;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationItem;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPlan;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPreviewRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationSummary;
import br.com.jorgemelo.nimbusfilemanager.organization.application.filter.OrganizationCandidateFilter;
import br.com.jorgemelo.nimbusfilemanager.organization.application.model.OrganizationStatistics;
import br.com.jorgemelo.nimbusfilemanager.organization.application.resolver.OrganizationConflictDetector;
import br.com.jorgemelo.nimbusfilemanager.organization.application.resolver.OrganizationDestinationResolver;
import br.com.jorgemelo.nimbusfilemanager.organization.application.resolver.OrganizationLayoutResolver;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.OrganizationCandidateRepository;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.projection.OrganizationCandidate;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.shared.util.NumberUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PageUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class OrganizationPlanner {

	private final OrganizationCandidateRepository organizationCandidateRepository;
	private final OrganizationDestinationResolver destinationResolver;
	private final OrganizationLayoutResolver layoutResolver;
	private final OrganizationConflictDetector conflictDetector;
	private final OrganizationCandidateFilter candidateFilter;
	private final ExecutionProgressService executionProgressService;
	private final ExecutionCancellationService executionCancellationService;
	private final MediaLocationService mediaLocationService;
	private final LocationOrganizationPolicy locationOrganizationPolicy;

	@Autowired
	public OrganizationPlanner(OrganizationCandidateRepository organizationCandidateRepository,
			OrganizationDestinationResolver destinationResolver, OrganizationLayoutResolver layoutResolver,
			OrganizationConflictDetector conflictDetector, OrganizationCandidateFilter candidateFilter,
			ExecutionProgressService executionProgressService,
			ExecutionCancellationService executionCancellationService, MediaLocationService mediaLocationService,
			LocationOrganizationPolicy locationOrganizationPolicy) {
		this.organizationCandidateRepository = organizationCandidateRepository;
		this.destinationResolver = destinationResolver;
		this.layoutResolver = layoutResolver;
		this.conflictDetector = conflictDetector;
		this.candidateFilter = candidateFilter;
		this.executionProgressService = executionProgressService;
		this.executionCancellationService = executionCancellationService;
		this.mediaLocationService = mediaLocationService;
		this.locationOrganizationPolicy = locationOrganizationPolicy;
	}

	/**
	 * Builds the plan, reporting to the row the execution is being run under.
	 *
	 * <p>
	 * There used to be a second form that took no taking, and the executor called
	 * that one - so planning a large folder was silent on screen and a preview only
	 * became cancellable once the plan already existed. Planning is part of a
	 * worker-owned execution and of nothing else, so the taking is required: there
	 * is no caller that has no row to report to.
	 *
	 * @param ownership the taking every report about the row is written under - the
	 * same instance the executor is running under, never one rebuilt from the
	 * execution's id
	 */
	public OrganizationPlan preview(OrganizationPreviewRequest request, ExecutionOwnership ownership) {
		Path sourcePath = request.source();

		Path targetPath = request.target();

		String sourcePathText = PathUtils.normalize(sourcePath);

		String targetPathText = PathUtils.normalize(targetPath);

		OrganizationLayout layout = request.layoutValue();

		String normalizedLayout = layoutResolver.normalize(layout);

		log.info(
				"Starting organization preview. sourcePath={}, targetPath={}, layout={}, recursive={}, skipAlreadyOrganized={}, limit={}, locationSubdivision={}",
				sourcePathText, targetPathText, layout, request.recursiveValue(), request.skipAlreadyOrganizedValue(),
				request.safeLimit(), request.locationSubdivisionValue());

		String descendantPattern = PathUtils.descendantLikePattern(sourcePathText,
				sourcePath.getFileSystem().getSeparator());

		Page<OrganizationCandidate> page = organizationCandidateRepository.findCandidates(sourcePathText,
				descendantPattern, PageUtils.firstPage(request.safeLimit()));

		log.info("Loaded {} organization candidates.", page.getNumberOfElements());

		Map<Long, MediaGeoLocation> locations = loadLocations(request, page.getContent());

		try {
			List<OrganizationItem> items = new ArrayList<>();

			OrganizationStatistics statistics = new OrganizationStatistics();

			for (OrganizationCandidate candidate : page.getContent()) {
				stopIfReplaced(ownership);

				if (isCancelled(ownership)) {
					throw new ExecutionCancelledException("Preview cancelled by user.");
				}

				statistics.incrementProcessed();

				if (candidateFilter.matches(candidate, request, sourcePathText)) {
					OrganizationItem item = toItem(targetPath, normalizedLayout, candidate, request, locations);

					statistics.add(item);

					if (!item.samePath() || !request.skipAlreadyOrganizedValue()) {
						items.add(item);
					}
				}

				logProgress(statistics, candidate, ownership);
			}

			log.info("Detecting organization conflicts. items={}", items.size());

			items = conflictDetector.detect(items);

			OrganizationSummary summary = statistics.toSummary(items);

			log.info(
					"Organization preview finished. processed={} totalFiles={} filesWithDate={} filesWithoutDate={} alreadyOrganized={} plannedMoves={} conflicts={} targetAlreadyExists={} duplicateTargets={}",
					statistics.processed(), summary.totalFiles(), summary.filesWithDate(), summary.filesWithoutDate(),
					summary.alreadyOrganized(), summary.plannedMoves(), summary.conflicts(),
					summary.targetAlreadyExists(), summary.duplicateTargets());

			return new OrganizationPlan(sourcePathText, targetPathText, layout, false, summary, items);
		} finally {
			forget(ownership);
		}
	}

	/**
	 * Bulk-loads the resolved locations for this preview (one query for the whole
	 * page) when the geographic subdivision is enabled. The planner only talks to
	 * the geolocation facade - it never knows the provider.
	 */
	private Map<Long, MediaGeoLocation> loadLocations(OrganizationPreviewRequest request,
			List<OrganizationCandidate> candidates) {
		if (request.locationSubdivisionValue() == LocationSubdivision.NONE) {
			return Map.of();
		}

		List<Long> ids = candidates.stream().map(OrganizationCandidate::internalCatalogFileId).filter(Objects::nonNull)
				.toList();

		return mediaLocationService.locationsOf(ids);
	}

	/**
	 * Drops the cached cancellation answer once the preview is over, whether it
	 * finished or was stopped. Housekeeping of a cache, not of state: what a
	 * cancellation means is on the row.
	 */
	private void forget(ExecutionOwnership ownership) {
		executionCancellationService.forget(ownership.executionId());
	}

	private boolean isCancelled(ExecutionOwnership ownership) {
		return executionCancellationService.isCancelled(ownership.executionId());
	}

	/**
	 * Stops a plan whose execution has been taken over, at the same place a
	 * cancellation stops it.
	 *
	 * <p>
	 * Cooperative and answered from memory: it is here so a run that has been
	 * replaced stops spending minutes on a plan nobody will accept, not to make
	 * anything safe. What keeps its reports off the row is the taking each of them
	 * is written under - {@code ExecutionProgressService} refuses a taking that is
	 * no longer current - and this could be removed without a single write landing
	 * that should not have.
	 */
	private void stopIfReplaced(ExecutionOwnership ownership) {
		if (!ownership.takingIsStillCurrent()) {
			throw new OwnershipLostException("Execution " + ownership.executionId()
					+ " has been taken over, and this plan will not go on");
		}
	}

	private void logProgress(OrganizationStatistics statistics, OrganizationCandidate candidate,
			ExecutionOwnership ownership) {
		if (statistics.processed() != 1 && statistics.processed() % 1000 != 0) {
			return;
		}

		executionProgressService.updateProgress(ownership, statistics.processed(), (int) statistics.plannedMoves(),
				(int) statistics.alreadyOrganized(), 0, candidate == null ? null : candidate.currentPath());
	}

	private OrganizationItem toItem(Path targetPath, String layout, OrganizationCandidate candidate,
			OrganizationPreviewRequest request, Map<Long, MediaGeoLocation> locations) {
		MediaGeoLocation location = locations.get(candidate.internalCatalogFileId());

		List<String> locationSegments = locationOrganizationPolicy.subdivisionSegments(location,
				request.locationSubdivisionValue(), request.locationMinConfidence(), request.locationFallbackValue());

		OrganizationDestination destination = locationSegments.isEmpty()
				? destinationResolver.resolve(targetPath, layout, candidate)
				: destinationResolver.resolve(targetPath, layout, candidate, locationSegments);

		String sourcePath = PathUtils.normalize(candidate.currentPath());

		String destinationPath = PathUtils.normalize(destination.file());

		return new OrganizationItem(candidate.internalCatalogFileId(), candidate.catalogFileId(), candidate.fileName(),
				sourcePath, destinationPath, destination.date().yearMonth(), destination.date().day(),
				FileCategory.folderNameOf(destination.ruleResult().category()),
				MediaSubcategory.folderNameOf(destination.ruleResult().subcategory()),
				FileType.folderNameOf(destination.ruleResult().fileType()), destination.ruleResult().ruleName(),
				destination.ruleResult().reason() == null ? null : destination.ruleResult().reason().name(),
				NumberUtils.zeroIfNull(candidate.sizeBytes()), sourcePath.equalsIgnoreCase(destinationPath),
				destination.date().missingDate(), false, false, false, null,
				locationOrganizationPolicy.displayLabel(location),
				locationOrganizationPolicy.confidenceLabel(location));
	}
}