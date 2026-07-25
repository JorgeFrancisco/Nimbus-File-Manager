package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.date.DateSourceService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.FileHashes;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.FileSystemDates;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MissingEntry;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileIssueResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.RenameCandidate;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.RenamePair;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.ExtensionUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * Best-effort auto-repair, split out of {@link OrganizationReconcileService} so
 * that class stays focused on diagnostics (disk vs. database comparison) while
 * this one owns the separate concern of pairing up disappearances with new
 * files and relocating the matching record instead of letting it be marked
 * missing.
 *
 * <p>
 * The JDK's WatchService has no rename event kind, so an OS-level rename
 * reaches InventoryWatchService as a plain delete+create pair,
 * indistinguishable from an unrelated delete followed by an unrelated create
 * elsewhere. {@link #detectAndApplyRenames} pairs up "missing on disk"
 * catalog_file_location rows against "missing in database" disk paths by
 * content (size + sha256) and, only when a pairing is unambiguous, relocates
 * the existing CatalogFile/CatalogFileLocation instead of letting the caller
 * mark it missing while the next inventory creates a duplicate for the new
 * path.
 */
@Slf4j
@Service
public class OrganizationRenameDetectionService {

	private final CatalogFileRepository catalogFileRepository;
	private final FileHashService fileHashService;
	private final DateSourceService dateSourceService;
	private final Clock clock;

	public OrganizationRenameDetectionService(CatalogFileRepository catalogFileRepository,
			FileHashService fileHashService,
			DateSourceService dateSourceService, Clock clock) {
		this.catalogFileRepository = catalogFileRepository;
		this.fileHashService = fileHashService;
		this.dateSourceService = dateSourceService;
		this.clock = clock;
	}

	/**
	 * @return the ids of CatalogFile records that were relocated, so the caller can
	 *         exclude them from its own "mark missing" pass.
	 */
	Set<Long> detectAndApplyRenames(OrganizationReconcileResponse response) {
		List<OrganizationReconcileIssueResponse> missingIssues = response.missingOnDiskSamples().stream()
				.filter(issue -> issue.catalogFileId() != null && issue.path() != null).toList();

		List<String> newPaths = response.missingInDatabaseSamples().stream()
				.map(OrganizationReconcileIssueResponse::path).filter(Objects::nonNull).distinct().toList();

		if (missingIssues.isEmpty() || newPaths.isEmpty()) {
			return Set.of();
		}

		List<Long> missingIds = missingIssues.stream().map(OrganizationReconcileIssueResponse::catalogFileId).distinct()
				.toList();

		Map<Long, CatalogFile> missingFilesById = catalogFileRepository.findForMetadataRebuildByIds(missingIds).stream()
				.filter(catalogFile -> catalogFile.getSha256() != null && catalogFile.getSizeBytes() != null)
				.collect(Collectors.toMap(CatalogFile::getId, catalogFile -> catalogFile));

		if (missingFilesById.isEmpty()) {
			return Set.of();
		}

		Map<String, List<MissingEntry>> missingByKey = groupMissingByKey(missingIssues, missingFilesById);

		Set<Long> matchableSizes = missingFilesById.values().stream().map(CatalogFile::getSizeBytes)
				.collect(Collectors.toSet());

		Map<String, List<RenameCandidate>> candidatesByKey = collectRenameCandidatesByKey(newPaths, matchableSizes);

		return pairAndApplyRenames(missingByKey, candidatesByKey);
	}

	/**
	 * Grouped by (size, sha256) with the exact missing currentPath kept alongside
	 * each CatalogFile, so only files whose placement is actually absent from disk
	 * are ever touched here.
	 */
	private Map<String, List<MissingEntry>> groupMissingByKey(List<OrganizationReconcileIssueResponse> missingIssues,
			Map<Long, CatalogFile> missingFilesById) {
		Map<String, List<MissingEntry>> missingByKey = new HashMap<>();

		for (OrganizationReconcileIssueResponse issue : missingIssues) {
			CatalogFile catalogFile = missingFilesById.get(issue.catalogFileId());

			if (catalogFile == null) {
				continue;
			}

			missingByKey
					.computeIfAbsent(matchKey(catalogFile.getSizeBytes(), catalogFile.getSha256()),
							_ -> new ArrayList<>())
					.add(new MissingEntry(catalogFile, issue.path()));
		}

		return missingByKey;
	}

	/**
	 * Only hash/stat a "missing in database" disk candidate when its size matches
	 * at least one missing-on-disk record - most new files never share a size with
	 * anything missing and are skipped without ever being read.
	 */
	private Map<String, List<RenameCandidate>> collectRenameCandidatesByKey(List<String> newPaths,
			Set<Long> matchableSizes) {
		Map<String, List<RenameCandidate>> candidatesByKey = new HashMap<>();

		for (String newPath : newPaths) {
			Path path = PathUtils.normalizePath(newPath);

			Long size = sizeOrNull(path);

			if (size == null || !matchableSizes.contains(size)) {
				continue;
			}

			FileHashes hashes = hashesOrNull(path);

			if (hashes != null) {
				candidatesByKey.computeIfAbsent(matchKey(size, hashes.sha256()), _ -> new ArrayList<>())
						.add(new RenameCandidate(path, datesOrNull(path)));
			}
		}

		return candidatesByKey;
	}

	/**
	 * Walks each (size, sha256) group that has both missing records and disk
	 * candidates, applying only the unambiguous pairings and collecting the ids of
	 * the CatalogFile records that were actually relocated.
	 */
	private Set<Long> pairAndApplyRenames(Map<String, List<MissingEntry>> missingByKey,
			Map<String, List<RenameCandidate>> candidatesByKey) {
		Set<Long> renamedIds = new HashSet<>();

		for (Map.Entry<String, List<MissingEntry>> entry : missingByKey.entrySet()) {
			List<RenameCandidate> candidates = candidatesByKey.get(entry.getKey());

			if (candidates == null || candidates.isEmpty()) {
				continue;
			}

			for (RenamePair pair : resolveUnambiguousPairs(entry.getValue(), candidates)) {
				if (applyRename(pair.entry(), pair.candidate())) {
					renamedIds.add(pair.entry().catalogFile().getId());
				}
			}
		}

		return renamedIds;
	}

	/**
	 * A same size+hash group is unambiguous outright when exactly one missing entry
	 * and one disk candidate share it - the common case, one CatalogFileLocation
	 * went missing and one new file appeared with identical content. When several
	 * share it (equal-content files, e.g. more than one empty file), each entry is
	 * only paired when exactly one candidate also has its exact filesystem creation
	 * time, which a true rename preserves untouched on NTFS; anything still
	 * ambiguous after that tie-break is left alone, since a wrong merge is worse
	 * than a missed one.
	 */
	private List<RenamePair> resolveUnambiguousPairs(List<MissingEntry> group, List<RenameCandidate> candidates) {
		if (group.size() == 1 && candidates.size() == 1) {
			return List.of(new RenamePair(group.get(0), candidates.get(0)));
		}

		List<RenamePair> pairs = new ArrayList<>();

		Set<RenameCandidate> claimed = new HashSet<>();

		for (MissingEntry missingEntry : group) {
			LocalDateTime createdAt = missingEntry.catalogFile().getCreatedAt();

			if (createdAt == null) {
				continue;
			}

			List<RenameCandidate> sameCreatedAt = candidates.stream().filter(candidate -> !claimed.contains(candidate))
					.filter(candidate -> candidate.dates() != null && createdAt.equals(candidate.dates().createdAt()))
					.toList();

			if (sameCreatedAt.size() == 1) {
				RenameCandidate candidate = sameCreatedAt.get(0);

				pairs.add(new RenamePair(missingEntry, candidate));

				claimed.add(candidate);
			}
		}

		return pairs;
	}

	/**
	 * Relocates the CatalogFile/matched CatalogFileLocation to the new path instead
	 * of re-running metadata extraction - the content hash already matched, so
	 * lastAnalysis/analysisVersion are left as-is. Mirrors CatalogFileMapper's
	 * existing convention of keeping originalPath/originalFolder mirrored to the
	 * current ones on every update, rather than treating "original" as immutable
	 * history.
	 */
	private boolean applyRename(MissingEntry missingEntry, RenameCandidate candidate) {
		Path parent = candidate.path().getParent();

		if (parent == null) {
			return false;
		}

		CatalogFile catalogFile = missingEntry.catalogFile();

		CatalogFileLocation location = catalogFile.getLocation();

		if (location == null || !missingEntry.missingPath().equals(PathUtils.normalize(location.getCurrentPath()))) {
			return false;
		}

		String newFileKey = PathUtils.normalize(candidate.path());
		String newFolder = PathUtils.normalize(parent);

		catalogFile.setFileKey(newFileKey);
		catalogFile.setFileName(candidate.path().getFileName().toString());
		catalogFile.setExtension(ExtensionUtils.fromPath(candidate.path()));
		catalogFile.markActive();

		if (candidate.dates() != null && candidate.dates().modifiedAt() != null) {
			catalogFile.setModifiedAt(candidate.dates().modifiedAt());
		}

		location.setCurrentPath(newFileKey);
		location.setCurrentFolder(newFolder);
		location.setOriginalPath(newFileKey);
		location.setOriginalFolder(newFolder);
		location.setUpdatedAt(LocalDateTime.now(clock));

		catalogFileRepository.save(catalogFile);

		log.info("Detected rename: catalog_file {} relocated from {} to {}", catalogFile.getId(),
				missingEntry.missingPath(), newFileKey);

		return true;
	}

	private String matchKey(Long size, String sha256) {
		return size + "|" + sha256;
	}

	private Long sizeOrNull(Path path) {
		try {
			return Files.size(path);
		} catch (IOException _) {
			return null;
		}
	}

	private FileHashes hashesOrNull(Path path) {
		try {
			return fileHashService.hashes(path);
		} catch (RuntimeException e) {
			log.warn("Could not hash rename candidate {}", path, e);

			return null;
		}
	}

	private FileSystemDates datesOrNull(Path path) {
		try {
			return dateSourceService.resolveFileSystemDates(path);
		} catch (RuntimeException _) {
			return null;
		}
	}
}