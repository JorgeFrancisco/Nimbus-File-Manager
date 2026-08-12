package br.com.jorgemelo.nimbusfilemanager.inventory.application.dto;

import java.nio.file.Path;

import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.LocationMatch;

/**
 * Which catalogued place an observed change turned out to be about, and how
 * firmly.
 *
 * <p>
 * The match travels beside the row rather than the caller inferring it from a
 * null, so that "more than one" cannot be read as "none" - the two lead to
 * opposite decisions, and only one of them is safe to act on.
 *
 * @param catalogFileId the file, present only for {@link LocationMatch#UNIQUE}
 * @param currentPath where the catalog believes that file is, which is what the
 * door is told to check before it writes
 * @param byIdentity whether the filesystem's identity established this, rather
 * than the path the change said it came from. It decides which proof the
 * resulting fact records
 */
public record RecognizedLocation(LocationMatch match, Long catalogFileId, Path currentPath, boolean byIdentity) {
}