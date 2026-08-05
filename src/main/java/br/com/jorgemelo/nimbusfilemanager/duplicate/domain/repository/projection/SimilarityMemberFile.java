package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection;

import java.time.LocalDateTime;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;

/**
 * The catalog side of a published group member, read when the screen renders
 * rather than frozen into the result.
 *
 * <p>
 * A published analysis stores which files it grouped and what it decided about
 * them - never their names, paths or sizes. Those are read from the catalog now,
 * so a file renamed or moved after the analysis shows where it is today. The
 * grouping is a photograph of a decision, not of a filesystem.
 *
 * <p>
 * {@code lifecycleStatus} is here for the question the screen has to answer
 * before offering a button: a member that was deleted or sent to quarantine
 * since the analysis is still part of the result - the analysis was not wrong -
 * but it is not something the user can act on any more.
 */
public record SimilarityMemberFile(UUID publicId, String fileName, String extension, String fileType, Long sizeBytes,
		String currentPath, String currentFolder, LocalDateTime modifiedAt, Integer width, Integer height,
		LocalDateTime captureDate, DateSource dateSource, LifecycleStatus lifecycleStatus) {

	/**
	 * Whether the screen may offer actions over this file. Only an active member
	 * can be deleted or excluded; anything else is shown as part of the history of
	 * the group and nothing more.
	 */
	public boolean actionable() {
		return lifecycleStatus == LifecycleStatus.ACTIVE;
	}
}