package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MissingFile;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileIssueResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.Scan;
import br.com.jorgemelo.nimbusfilemanager.shared.application.LocationChangeException;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.CatalogFactProvenance;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.LocationChange;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.KnownContentBatchRow;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.CatalogLocationWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;
import lombok.extern.slf4j.Slf4j;

/**
 * A catalogued file that left its path, recognised again by its bytes when the
 * file system could not say where it went.
 *
 * <p>
 * On Windows nothing here is needed: the journal and the live watch both pair a
 * rename themselves and carry the object's own identity, and the recognition
 * acts on that within a poll. Elsewhere - Linux, which is what the container
 * image runs, and any {@code WatchService} platform - a rename arrives as a
 * deletion and an arrival that name each other in no way at all. Without this,
 * every hand-made rename on those systems costs the file its identity: the row
 * is marked missing, an inventory catalogues the new path as a stranger, and
 * with it go the file's history, its public identifier, the duplicate decisions
 * made about it and every fingerprint anyone paid to compute.
 *
 * <p>
 * <b>What it is allowed to conclude.</b> Only this: the bytes one catalogued
 * file is recorded as holding were found at exactly one path nobody has
 * catalogued, and no other file the catalog holds has those bytes. That is not
 * proof of a rename - a copy followed by a deletion leaves the same evidence -
 * but it is the same outcome for the person looking: those bytes were there and
 * are now here, and nowhere else. Where the answer is anything but sole, nothing
 * is concluded, because merging two files that merely look alike is a lie the
 * catalog would never correct, while missing a merge costs an analysis and says
 * nothing false.
 *
 * <p>
 * <b>Why it runs on the walk and not on the notifications.</b> Correlating a
 * deletion with an arrival as they are reported means holding candidates in
 * memory, guessing how long to wait, and being wrong whenever the two halves
 * land in different polls, arrive out of order, or are separated by a restart.
 * The walk has none of those problems because it is not a correlation at all:
 * it compares two complete sets, so there are no halves to miss and no window to
 * choose. It costs a pass of latency and buys away an entire class of race.
 */
@Slf4j
@Service
public class RelocationByContent {

	private final CatalogFileLocationRepository catalogFileLocationRepository;
	private final CatalogFileRepository catalogFileRepository;
	private final CatalogLocationWriter catalogLocationWriter;
	private final FileHashService fileHashService;
	private final Clock clock;

	public RelocationByContent(CatalogFileLocationRepository catalogFileLocationRepository,
			CatalogFileRepository catalogFileRepository, CatalogLocationWriter catalogLocationWriter,
			FileHashService fileHashService, Clock clock) {
		this.catalogFileLocationRepository = catalogFileLocationRepository;
		this.catalogFileRepository = catalogFileRepository;
		this.catalogLocationWriter = catalogLocationWriter;
		this.fileHashService = fileHashService;
		this.clock = clock;
	}

	/**
	 * @return the same scan with whatever was recovered taken out of it, so the
	 * convergence that follows neither records those files as missing nor asks for
	 * an inventory that would catalogue them a second time
	 */
	public Scan recover(Scan scan) {
		if (scan.missingFiles().isEmpty() || scan.physicalOnly().isEmpty()) {
			return scan;
		}

		Map<String, KnownContentBatchRow> lost = soleClaimants(scan.missingFiles());

		if (lost.isEmpty()) {
			return scan;
		}

		Map<String, Path> found = soleCandidates(scan.physicalOnly(), lost);

		if (found.isEmpty()) {
			return scan;
		}

		Instant observedAt = Instant.now(clock);

		Set<Long> recovered = new HashSet<>();
		Set<String> taken = new HashSet<>();

		for (Map.Entry<String, Path> candidate : found.entrySet()) {
			KnownContentBatchRow missing = lost.get(candidate.getKey());

			if (relocate(missing, candidate.getValue(), observedAt)) {
				recovered.add(missing.getCatalogFileId());
				taken.add(PathUtils.normalize(candidate.getValue()));
			}
		}

		if (recovered.isEmpty()) {
			return scan;
		}

		log.info("Reconciliation recognised {} file(s) by their content at a path the catalog did not know",
				recovered.size());

		return without(scan, recovered, taken);
	}

	/**
	 * The lost files whose bytes name them and nothing else.
	 *
	 * <p>
	 * Three conditions, and every one of them is about being alone. A file with no
	 * digest on record has nothing to be recognised by. A digest two lost files
	 * share cannot tell which of them arrived somewhere. And a digest some file
	 * still in the catalog also holds is a digest that proves nothing about where
	 * anything went - a library of photographs is full of exact duplicates, and
	 * this is the condition that keeps them from being merged into each other.
	 */
	private Map<String, KnownContentBatchRow> soleClaimants(List<MissingFile> missing) {
		String[] paths = missing.stream().map(MissingFile::currentPath).toArray(String[]::new);

		List<KnownContentBatchRow> rows = catalogFileLocationRepository
				.findKnownContentByPaths(paths, flavorOf(paths[0]).name()).stream()
				.filter(row -> row.getSha256() != null && row.getSizeBytes() != null).toList();

		Map<String, KnownContentBatchRow> byDigest = new LinkedHashMap<>();
		Set<String> shared = new HashSet<>();

		for (KnownContentBatchRow row : rows) {
			if (byDigest.put(row.getSha256(), row) != null) {
				shared.add(row.getSha256());
			}
		}

		shared.forEach(byDigest::remove);

		if (byDigest.isEmpty()) {
			return byDigest;
		}

		Set<String> heldElsewhere = new HashSet<>(
				catalogFileRepository.digestsHeldMoreThanOnce(byDigest.keySet().toArray(String[]::new)));

		heldElsewhere.forEach(byDigest::remove);

		return byDigest;
	}

	/**
	 * The uncatalogued paths whose bytes answer to a lost file, and only where one
	 * of them does.
	 *
	 * <p>
	 * The size is asked first and the digest only of what it does not rule out. A
	 * new file almost never shares its exact size with something that went
	 * missing, so this is what keeps a walk that found a hundred thousand strangers
	 * from reading a hundred thousand files - and where it does read them, every
	 * reading either recovers a file's whole history or proves the file is new,
	 * both of which are worth more than the reading costs.
	 */
	private Map<String, Path> soleCandidates(List<String> physicalOnly, Map<String, KnownContentBatchRow> lost) {
		Set<Long> sizes = new HashSet<>();

		lost.values().forEach(row -> sizes.add(row.getSizeBytes()));

		Map<String, Path> byDigest = new LinkedHashMap<>();
		Set<String> shared = new HashSet<>();

		for (String candidate : physicalOnly) {
			Path path = PathUtils.normalizePath(candidate);

			Long size = sizeOf(path);

			if (size == null || !sizes.contains(size)) {
				continue;
			}

			String digest = digestOf(path);

			if (digest == null || !lost.containsKey(digest)) {
				continue;
			}

			if (byDigest.put(digest, path) != null) {
				shared.add(digest);
			}
		}

		shared.forEach(byDigest::remove);

		return byDigest;
	}

	/**
	 * Applied through the same door every other location change goes through, so
	 * whether this was a rename or a move is decided where that is already decided,
	 * and the fact and the row commit together.
	 *
	 * <p>
	 * Nothing is said about the content: the digest matched what was already on
	 * record, which is exactly the case in which there is nothing to learn and no
	 * generation to advance. And no file-system identity is recorded either - none
	 * was observed, and a source that cannot pair a rename is not a source that
	 * offers one.
	 */
	private boolean relocate(KnownContentBatchRow missing, Path newPath, Instant observedAt) {
		CatalogFactProvenance provenance = new CatalogFactProvenance(observedAt, CatalogEventSources.RECONCILE,
				CatalogEventEvidence.SOLE_CONTENT_MATCH, null);

		try {
			catalogLocationWriter.relocate(new LocationChange(missing.getCatalogFileId(), UuidV7.generate(),
					PathUtils.normalizePath(missing.getInputPath()), newPath, provenance));

			log.debug("Catalog file {} was recognised by its content at {}", missing.getCatalogFileId(), newPath);

			return true;
		} catch (LocationChangeException refusal) {
			// The catalog moved under the walk - the file turned up by another route, or
			// the destination filled. The pass that runs next compares a world that has
			// stopped moving.
			log.info("Could not repoint catalog file {} to {}: {}", missing.getCatalogFileId(), newPath,
					refusal.getMessage());

			return false;
		}
	}

	/**
	 * The scan minus what was repaired - counts, samples and work sets alike, so
	 * nothing downstream acts on a difference that no longer exists and no screen
	 * lists a file as missing that was put back a moment ago.
	 */
	private static Scan without(Scan scan, Set<Long> recovered, Set<String> taken) {
		OrganizationReconcileResponse response = scan.response();

		List<MissingFile> missing = scan.missingFiles().stream()
				.filter(file -> !recovered.contains(file.catalogFileId())).toList();

		List<String> physicalOnly = scan.physicalOnly().stream().filter(path -> !taken.contains(path)).toList();

		OrganizationReconcileResponse repaired = new OrganizationReconcileResponse(response.sourcePath(),
				response.recursive(), response.includeHidden(), response.filesOnDisk(), response.filesInDatabase(),
				missing.size(), physicalOnly.size(), samplesWithout(response.missingOnDiskSamples(), recovered, taken),
				samplesWithout(response.missingInDatabaseSamples(), recovered, taken), recovered.size(), 0, 0, 0);

		return new Scan(repaired, missing, physicalOnly, scan.contentSuspects());
	}

	private static List<OrganizationReconcileIssueResponse> samplesWithout(
			List<OrganizationReconcileIssueResponse> samples, Set<Long> recovered, Set<String> taken) {
		List<OrganizationReconcileIssueResponse> kept = new ArrayList<>(samples.size());

		for (OrganizationReconcileIssueResponse sample : samples) {
			boolean repaired = recovered.contains(sample.catalogFileId())
					|| sample.path() != null && taken.contains(PathUtils.normalize(sample.path()));

			if (!repaired) {
				kept.add(sample);
			}
		}

		return List.copyOf(kept);
	}

	private static PathFlavor flavorOf(String path) {
		return PathFlavor.of(PathUtils.normalizePath(path));
	}

	private static Long sizeOf(Path path) {
		try {
			return Files.size(path);
		} catch (IOException _) {
			return null;
		}
	}

	private String digestOf(Path path) {
		try {
			return fileHashService.sha256(path);
		} catch (RuntimeException exception) {
			log.debug("Could not read {} while looking for a file the catalog lost", path, exception);

			return null;
		}
	}
}