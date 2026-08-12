package br.com.jorgemelo.nimbusfilemanager.inventory.application.dto;

import java.nio.file.Path;
import java.time.Instant;

/**
 * A file the walk found, with the two facts the walk was already told about it.
 *
 * <p>
 * The size and the modification time come from the same {@code
 * BasicFileAttributes} the file visitor receives and used to discard. Carrying
 * them costs nothing - the operating system had already produced them to answer
 * the walk - and it is what lets a scan notice that a catalogued file is not the
 * file it catalogued without opening anything.
 */
public record ScannedFile(Path path, long sizeBytes, Instant modifiedAt) {
}