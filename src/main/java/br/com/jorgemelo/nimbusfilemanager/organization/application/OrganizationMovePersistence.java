package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Atomic persistence of a successful physical move.
 *
 * <p>
 * The catalog update ({@link CatalogFile#setFileKey}, the
 * {@link CatalogFileLocation} path) and the {@code MOVED} {@link Movement}
 * record are written in a <em>single</em> transaction, so the catalog and its
 * audit trail can never diverge: either the file's new location and its
 * movement both commit, or neither does. This is the transactional boundary the
 * reliability work requires - the physical {@code Files.move} happens before
 * this call, and the executor rolls the file back on disk if this transaction
 * throws.
 *
 * <p>
 * Lives in its own bean (not inside {@link OrganizationExecutor}) so the
 * {@code @Transactional} proxy is honored - a self-invoked annotated method
 * would run without a transaction.
 */
@Service
public class OrganizationMovePersistence {

	private final CatalogFileRepository catalogFileRepository;
	private final CatalogFileLocationRepository catalogFileLocationRepository;
	private final MovementRepository movementRepository;
	private final Clock clock;

	@Autowired
	public OrganizationMovePersistence(CatalogFileRepository catalogFileRepository,
			CatalogFileLocationRepository catalogFileLocationRepository, MovementRepository movementRepository,
			Clock clock) {
		this.catalogFileRepository = catalogFileRepository;
		this.catalogFileLocationRepository = catalogFileLocationRepository;
		this.movementRepository = movementRepository;
		this.clock = clock;
	}

	/**
	 * Points the catalog at {@code target} and records the {@code MOVED} movement
	 * in the same transaction. Returns the updated {@link CatalogFile}.
	 */
	@Transactional
	public CatalogFile persistSuccessfulMove(Execution execution, CatalogFile catalogFile, CatalogFileLocation location,
			Path source, Path target) {
		Path parent = requireParent(target);

		catalogFile.setFileKey(PathUtils.normalize(target));
		catalogFile.setFileName(target.getFileName().toString());
		catalogFile.setModifiedAt(readLastModifiedTime(target, catalogFile.getModifiedAt()));

		location.setCurrentPath(PathUtils.normalize(target));
		location.setCurrentFolder(PathUtils.normalize(parent));
		location.setUpdatedAt(LocalDateTime.now(clock));

		catalogFileLocationRepository.save(location);

		catalogFileRepository.save(catalogFile);

		movementRepository.save(
				Movement.builder().execution(execution).catalogFile(catalogFile).sourcePath(PathUtils.normalize(source))
						.targetPath(PathUtils.normalize(target)).status(MovementStatus.MOVED).reason(null).build());

		return catalogFile;
	}

	private Path requireParent(Path target) {
		Path parent = target.getParent();

		if (parent == null) {
			throw new IllegalStateException("An organization target path must have a parent directory: " + target);
		}

		return parent;
	}

	private LocalDateTime readLastModifiedTime(Path file, LocalDateTime fallback) {
		try {
			return LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault());
		} catch (IOException _) {
			return fallback;
		}
	}
}