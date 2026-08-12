package br.com.jorgemelo.nimbusfilemanager.conversion.application.dto;

import java.nio.file.Path;

import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MoveBaseline;

/**
 * The converted file in the library, and what the move that put it there
 * proved about it.
 *
 * <p>
 * The baseline travels because it was already paid for: the verified move reads
 * the whole file to capture it and reads the whole file again to check what
 * arrived, so by the time the encode is in place its digest is a fact somebody
 * spent minutes establishing. Discarding it meant the catalog either recorded
 * the new file with no digest at all, or read every byte a third time to learn
 * one it had already been told.
 *
 * @param proven the size and digest the move verified at {@link #path}
 */
public record PlacedConversion(Path path, MoveBaseline proven) {
}