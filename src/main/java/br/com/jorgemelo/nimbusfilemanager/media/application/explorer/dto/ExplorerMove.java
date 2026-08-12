package br.com.jorgemelo.nimbusfilemanager.media.application.explorer.dto;

import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MoveBaseline;

/**
 * Whether the rename happened on disk, and what it proved about the bytes.
 *
 * <p>
 * Two things because they are independent: a folder rename succeeds and proves
 * nothing - a folder has no bytes of its own - while a file rename succeeds and
 * proves exactly what the file contains, at no extra cost, because the secure
 * move reads it twice to verify itself.
 *
 * @param baseline what the move proved, or null for a folder and for a failure
 */
public record ExplorerMove(boolean done, MoveBaseline baseline) {
}