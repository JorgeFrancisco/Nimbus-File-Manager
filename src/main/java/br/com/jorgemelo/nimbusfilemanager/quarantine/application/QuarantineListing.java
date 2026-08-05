package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineItemResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.FilePreviewSupport;
import br.com.jorgemelo.nimbusfilemanager.shared.util.FileTypeIcon;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.SizeFormatter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.enums.Kind;

/**
 * What the Quarentena screen shows: the files currently held there, newest
 * deletion first, driven by the {@code Movement} audit rows that recorded each
 * soft-delete.
 *
 * <p>
 * Separate from the restoring for a reason that is not tidiness. Reading is
 * what a screen does; restoring changes the user's files and belongs to the
 * worker, and a class that did both would hand every screen listing quarantined
 * items the capability to move them. Splitting them is what lets the listing
 * stay where it is asked for while the moving lives where it is carried out.
 */
@Service
public class QuarantineListing {

	private final MovementRepository movementRepository;

	public QuarantineListing(MovementRepository movementRepository) {
		this.movementRepository = movementRepository;
	}

	/** One page of files currently held in quarantine, newest deletion first. */
	@Transactional(readOnly = true)
	public Page<QuarantineItemResponse> list(Pageable pageable) {
		return movementRepository.findByStatusAndReasonInOrderByIdDesc(MovementStatus.MOVED,
				QuarantineConstants.QUARANTINED_REASONS, pageable).map(this::toItem);
	}

	private QuarantineItemResponse toItem(Movement movement) {
		Path original = PathUtils.normalizePath(movement.getSourcePath());
		Path quarantine = PathUtils.normalizePath(movement.getTargetPath());
		Path originFolder = original.getParent();

		CatalogFile catalogFile = movement.getCatalogFile();

		FileType fileType = catalogFile == null ? null : catalogFile.getFileType();

		String typeName = fileType == null ? "OTHER" : fileType.name();

		Long sizeBytes = sizeOf(catalogFile, quarantine);

		UUID mediaPublicId = catalogFile == null ? null : catalogFile.getPublicId();

		boolean present = Files.exists(quarantine);

		Kind kind = FilePreviewSupport.kind(typeName, extensionOf(original));

		// Served by public id through /api/media (which allows soft-deleted files for
		// logged-in users); the card builds the thumbnail URL from the public id, this
		// is the open-in-lightbox content URL.
		boolean previewable = mediaPublicId != null && kind != Kind.NONE;

		String previewUrl = previewable ? "/api/media/" + mediaPublicId + "/content" : null;

		Path quarantineFolder = quarantine.getParent();

		return new QuarantineItemResponse(movement.getPublicId(), movement.getExecution().getPublicId(), mediaPublicId,
				original.getFileName().toString(), PathUtils.normalize(original),
				originFolder == null ? null : PathUtils.normalize(originFolder), PathUtils.normalize(quarantine),
				quarantineFolder == null ? null : PathUtils.normalize(quarantineFolder), sizeBytes,
				sizeBytes == null ? "—" : SizeFormatter.format(sizeBytes), movement.getMovedAt(), present,
				originFolder != null && Files.isDirectory(originFolder), Files.exists(original), typeName,
				FileTypeIcon.iconClass(typeName), FileTypeIcon.iconLabelKey(typeName), kind == Kind.IMAGE,
				kind == Kind.VIDEO, kind == Kind.PDF, kind == Kind.TEXT, kind == Kind.AUDIO, previewUrl);
	}

	private String extensionOf(Path path) {
		String fileName = path.getFileName() == null ? "" : path.getFileName().toString();

		int dot = fileName.lastIndexOf('.');

		return dot >= 0 && dot < fileName.length() - 1 ? fileName.substring(dot + 1) : "";
	}

	private Long sizeOf(CatalogFile catalogFile, Path quarantine) {
		if (catalogFile != null && catalogFile.getSizeBytes() != null) {
			return catalogFile.getSizeBytes();
		}

		try {
			return Files.exists(quarantine) ? Files.size(quarantine) : null;
		} catch (IOException _) {
			return null;
		}
	}
}