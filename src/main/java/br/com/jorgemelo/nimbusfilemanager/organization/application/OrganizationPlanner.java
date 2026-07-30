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
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
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
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
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

	public OrganizationPlan preview(OrganizationPreviewRequest request) {
		return preview(request, null);
	}

	public OrganizationPlan preview(OrganizationPreviewRequest request, Execution execution) {
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

		if (execution != null) {
			executionProgressService.updateTotal(execution, page.getNumberOfElements());
		}

		register(execution);

		try {
			List<OrganizationItem> items = new ArrayList<>();

			OrganizationStatistics statistics = new OrganizationStatistics();

			for (OrganizationCandidate candidate : page.getContent()) {
				if (isCancelled(execution)) {
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

				logProgress(statistics, candidate, execution);
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
			unregister(execution);
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

	private void register(Execution execution) {
		if (execution != null) {
			executionCancellationService.register(execution.getId());
		}
	}

	private void unregister(Execution execution) {
		if (execution != null) {
			executionCancellationService.unregister(execution.getId());
		}
	}

	private boolean isCancelled(Execution execution) {
		return execution != null && executionCancellationService.isCancelled(execution.getId());
	}

	private void logProgress(OrganizationStatistics statistics, OrganizationCandidate candidate, Execution execution) {
		if (statistics.processed() != 1 && statistics.processed() % 1000 != 0) {
			return;
		}

		if (execution != null) {
			executionProgressService.updateProgress(execution, statistics.processed(), (int) statistics.plannedMoves(),
					(int) statistics.alreadyOrganized(), 0, candidate == null ? null : candidate.currentPath());
		}
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