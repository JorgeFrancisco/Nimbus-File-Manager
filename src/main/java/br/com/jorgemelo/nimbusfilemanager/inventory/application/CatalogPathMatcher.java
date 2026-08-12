package br.com.jorgemelo.nimbusfilemanager.inventory.application;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.CatalogPathMatch;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.CatalogPathMatchRow;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Asks the catalog, for a whole batch of scanned paths at once, which files it
 * already knows at each of them.
 *
 * <p>
 * It exists so the inventory never sees how a path is compared. The canonical
 * form belongs to the database - it is what the stored key was computed with -
 * and a service that had to know the rule would be a second place for it to be
 * written, and eventually a second answer.
 *
 * <p>
 * One query for the batch, whatever its size. The previous shape asked per file
 * in some paths and per batch in others; on a first inventory of a large library
 * the difference is the whole pass.
 */
@Component
public class CatalogPathMatcher {

	private final CatalogFileLocationRepository catalogFileLocationRepository;

	public CatalogPathMatcher(CatalogFileLocationRepository catalogFileLocationRepository) {
		this.catalogFileLocationRepository = catalogFileLocationRepository;
	}

	/**
	 * Keyed by the normalized path the caller passed in, so it can look its own
	 * files up without repeating any of the comparison.
	 *
	 * <p>
	 * Paths absent from the result are paths the catalog has never known - the
	 * caller does not have to distinguish "no row" from "no match".
	 */
	@Transactional(readOnly = true)
	public Map<String, CatalogPathMatch> match(List<Path> files) {
		if (files.isEmpty()) {
			return Map.of();
		}

		// One flavor for the batch: a scan walks one file system, and the rules a
		// path is read under belong to the file system it came from.
		PathFlavor flavor = PathFlavor.of(files.getFirst());

		String[] paths = files.stream().map(PathUtils::normalize).toArray(String[]::new);

		Map<String, List<Long>> present = new LinkedHashMap<>();
		Map<String, List<Long>> missing = new LinkedHashMap<>();

		for (CatalogPathMatchRow row : catalogFileLocationRepository.findLastKnownByPaths(paths, flavor.name())) {
			// A file somebody removed is not a file the catalog lost, and only the second
			// kind may be met again. Quarantining a photograph and then saving a new one
			// under the same name used to hand the new bytes to the old entry and bring it
			// back to life, undoing a decision the user made with a scan of a folder - so
			// a removed entry is not a candidate for anything found at its last address,
			// and what turns up there is a file the catalog is meeting for the first time.
			if (LifecycleStatus.DELETED.name().equals(row.getLifecycleStatus())) {
				continue;
			}

			Map<String, List<Long>> side = LifecycleStatus.ACTIVE.name().equals(row.getLifecycleStatus()) ? present
					: missing;

			side.computeIfAbsent(row.getInputPath(), _ -> new ArrayList<>()).add(row.getCatalogFileId());
		}

		Map<String, CatalogPathMatch> matches = new LinkedHashMap<>();

		for (String path : paths) {
			Long presentFile = onlyPresent(path, present.getOrDefault(path, List.of()));
			List<Long> missingFiles = missing.getOrDefault(path, List.of());

			if (presentFile == null && missingFiles.isEmpty()) {
				continue;
			}

			matches.put(path, new CatalogPathMatch(path, presentFile, List.copyOf(missingFiles)));
		}

		return matches;
	}

	/**
	 * Two files cannot both be present at one path, so finding two is not a choice
	 * to be made - it is damage, and the only honest thing to do with it is stop.
	 *
	 * <p>
	 * Picking one - the first, the lowest id, the newest - would hand one file's
	 * fingerprints, exclusions and history to another, and leave no trace that
	 * anything was wrong. Several <em>missing</em> files at one path is a different
	 * thing entirely: that is the catalog remembering honestly, and it is answered
	 * as ambiguity rather than as an error.
	 */
	private Long onlyPresent(String path, List<Long> presentFiles) {
		if (presentFiles.size() > 1) {
			throw new IllegalStateException(
					"More than one active catalog file occupies the same path: " + path + " " + presentFiles);
		}

		return presentFiles.isEmpty() ? null : presentFiles.getFirst();
	}
}