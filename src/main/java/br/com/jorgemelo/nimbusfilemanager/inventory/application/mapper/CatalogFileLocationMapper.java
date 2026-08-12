package br.com.jorgemelo.nimbusfilemanager.inventory.application.mapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * Where a file is, as a responsibility of its own.
 *
 * <p>
 * It used to be part of building the file itself, which is how the catalog ended
 * up with a path inside its identity. What a file <em>is</em> and where it
 * <em>happens to be</em> change for different reasons and at different moments,
 * so they are written by different code.
 *
 * <p>
 * Only two values are ever supplied here: the path and the rules it is spelled
 * under. The canonical key and the containing folder are the database's to
 * derive - asking this class for them would be asking it to agree with a
 * calculation it does not own.
 */
@Slf4j
@Component
public class CatalogFileLocationMapper {

	private final Clock clock;

	public CatalogFileLocationMapper(Clock clock) {
		this.clock = clock;
	}

	/**
	 * The placement of a file being catalogued for the first time.
	 *
	 * <p>
	 * The flavor comes from the file system the path was read from rather than
	 * from the running host, so a path observed through anything other than the
	 * default file system answers for itself.
	 */
	public CatalogFileLocation create(CatalogFile catalogFile, Path file) {
		Path normalized = PathUtils.normalizePath(file.toString());

		return CatalogFileLocation.builder().catalogFile(catalogFile).currentPath(PathUtils.normalize(normalized))
				.pathFlavor(PathFlavor.of(file)).updatedAt(Instant.now(clock)).build();
	}

	/**
	 * Confirms an existing placement, which is all a walk of the library is in a
	 * position to do.
	 *
	 * <p>
	 * It does not repoint anything. A scan reaches a row by the path it is at, so
	 * the two agree in every ordinary case; where they do not, the file is
	 * somewhere other than the catalog believes, and that is a move - which has an
	 * owner, produces a fact and is refused when the destination is taken. Writing
	 * it here would be a second way for a file to change place, one that leaves no
	 * history and asks nobody's permission, so it says so and leaves it to the
	 * reconciliation that recognises moves.
	 */
	public void update(CatalogFileLocation location, Path file) {
		String seen = PathUtils.normalize(PathUtils.normalizePath(file.toString()));

		if (!seen.equals(location.getCurrentPath())) {
			log.warn("The catalog places file {} at {}; leaving the difference to the reconciliation that "
					+ "recognises moves", seen, location.getCurrentPath());

			return;
		}

		location.setPathFlavor(PathFlavor.of(file));
		location.setUpdatedAt(Instant.now(clock));
	}
}