package br.com.jorgemelo.nimbusfilemanager.organization.application.resolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationItem;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Whether a plan would put two files in the same place, or one on top of a file
 * already there.
 *
 * <p>
 * Two targets are the same place under the rules of the file system they are on,
 * and that is asked of the file system itself: a {@link Path} compares the way
 * its own provider says paths compare, which is case-insensitively on Windows
 * and byte for byte on Linux. It used to be asked by lowercasing both, which is
 * the Windows answer given everywhere - so on Linux a plan that produced
 * {@code Foto.jpg} and {@code foto.JPG}, two files that can perfectly well
 * coexist, was rejected as a conflict with itself.
 */
@Component
public class OrganizationConflictDetector {

	public List<OrganizationItem> detect(List<OrganizationItem> items) {
		Map<Path, Long> targetOccurrences = items.stream().filter(item -> !item.samePath())
				.map(item -> PathUtils.normalizePath(item.targetPath()))
				.collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

		return items.stream().map(item -> detect(item, targetOccurrences)).toList();
	}

	private OrganizationItem detect(OrganizationItem item, Map<Path, Long> targetOccurrences) {
		if (item.samePath()) {
			return item.withConflict(false, false);
		}

		// A pre-existing target is only a blocking conflict when there is actually a
		// source file waiting to be moved onto it. When the source is already gone
		// (e.g. a re-run over a folder whose files were moved in a previous run, or a
		// crash that moved the file on disk but left the catalog behind), there is
		// nothing to move: moveOne() resolves it per item as ALREADY_MOVED (skip) or
		// SOURCE_NOT_FOUND. Counting it as a conflict here would wrongly REJECT the
		// whole batch and make legitimate re-runs impossible.
		boolean sourceExists = Files.exists(Path.of(item.sourcePath()));

		boolean targetExists = sourceExists && Files.exists(Path.of(item.targetPath()));

		boolean duplicateTarget = targetOccurrences
				.getOrDefault(PathUtils.normalizePath(item.targetPath()), 0L) > 1;

		return item.withConflict(targetExists, duplicateTarget);
	}
}