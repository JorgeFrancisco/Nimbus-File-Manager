package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileIssueResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.PathSync;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.Scan;
import br.com.jorgemelo.nimbusfilemanager.shared.application.catalog.CatalogMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * Write half of reconciliation, isolated so the long disk scan in
 * {@link OrganizationReconcileService} runs outside any transaction while only
 * these catalog mutations - rename merges, stale-path repairs and missing marks
 * - execute inside a short {@link Transactional} unit. Kept as a separate bean
 * on purpose: Spring's {@code @Transactional} does not apply to
 * self-invocation, so the orchestrator has to call the write step across a bean
 * boundary for the transaction to actually open.
 */
@Slf4j
@Component
public class ReconcileApplier {

	private final OrganizationRenameDetectionService renameDetectionService;
	private final CatalogFileLocationRepository catalogFileLocationRepository;
	private final CatalogMutations catalogMutations;
	private final Clock clock;

	public ReconcileApplier(OrganizationRenameDetectionService renameDetectionService,
			CatalogFileLocationRepository catalogFileLocationRepository, CatalogMutations catalogMutations,
			Clock clock) {
		this.renameDetectionService = renameDetectionService;
		this.catalogFileLocationRepository = catalogFileLocationRepository;
		this.catalogMutations = catalogMutations;
		this.clock = clock;
	}

	@Transactional
	public OrganizationReconcileResponse apply(Scan scan) {
		OrganizationReconcileResponse response = scan.response();

		Set<Long> renamedIds = renameDetectionService.detectAndApplyRenames(response);

		// Self-heal stragglers where a move updated catalog_file.file_key but left
		// catalog_file_location.current_path pointing at the old (now non-existent)
		// path - which made those files linger with a phantom path in Duplicados and
		// re-appear as ALREADY_MOVED skips on every run. Only synced when file_key is a
		// real file on disk and current_path is not, so a legitimately different row is
		// never touched.
		Set<Long> repairedIds = repairStalePaths(scan.pathSyncs());

		List<Long> missingIds = response.missingOnDiskSamples().stream()
				.map(OrganizationReconcileIssueResponse::catalogFileId).filter(Objects::nonNull)
				.filter(id -> !renamedIds.contains(id) && !repairedIds.contains(id)).distinct().toList();

		if (!missingIds.isEmpty()) {
			catalogMutations.markMissing(missingIds, LocalDateTime.now(clock));
		}

		return withRepairs(response, renamedIds.size(), repairedIds.size(), missingIds.size(),
				distinctRepaired(renamedIds, repairedIds, missingIds));
	}

	private Set<Long> repairStalePaths(List<PathSync> pathSyncs) {
		Set<Long> repaired = new HashSet<>();

		for (PathSync sync : pathSyncs) {
			catalogFileLocationRepository.findById(sync.catalogFileId()).ifPresent(location -> {
				Path target = PathUtils.normalizePath(sync.fileKey());
				Path parent = target.getParent();

				location.setCurrentPath(PathUtils.normalize(target));

				if (parent != null) {
					location.setCurrentFolder(PathUtils.normalize(parent));
				}

				location.setUpdatedAt(LocalDateTime.now(clock));

				catalogFileLocationRepository.save(location);

				repaired.add(sync.catalogFileId());

				log.info("Reconcile repaired stale current_path for catalog_file {} -> {}", sync.catalogFileId(),
						sync.fileKey());
			});
		}

		return repaired;
	}

	/**
	 * How many catalog entries were actually changed, counted once each.
	 *
	 * <p>
	 * Not the sum of the three. Marking missing already excludes anything renamed
	 * or repaired, but following a rename and syncing a stale path are decided
	 * independently and can land on the same entry in one pass - summing would
	 * report two repairs where one entry changed.
	 */
	private long distinctRepaired(Set<Long> renamedIds, Set<Long> repairedIds, List<Long> missingIds) {
		Set<Long> changed = new HashSet<>(renamedIds);

		changed.addAll(repairedIds);
		changed.addAll(missingIds);

		return changed.size();
	}

	private OrganizationReconcileResponse withRepairs(OrganizationReconcileResponse response, long renamed,
			long repairedPaths, long markedMissing, long repairedItems) {
		return new OrganizationReconcileResponse(response.sourcePath(), response.recursive(), response.includeHidden(),
				response.filesOnDisk(), response.filesInDatabase(), response.missingOnDisk(),
				response.missingInDatabase(), response.pathMismatches(), response.missingOnDiskSamples(),
				response.missingInDatabaseSamples(), response.pathMismatchSamples(), renamed, repairedPaths,
				markedMissing, repairedItems);
	}
}