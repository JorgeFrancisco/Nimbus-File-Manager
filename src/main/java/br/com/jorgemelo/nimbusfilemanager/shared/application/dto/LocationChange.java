package br.com.jorgemelo.nimbusfilemanager.shared.application.dto;

import java.nio.file.Path;
import java.util.UUID;

/**
 * One file going from one place to another, as the caller understands it.
 *
 * <p>
 * Paths rather than strings, so no caller has to remember to normalise them or
 * to work out which spelling rules they are under - the write door does both,
 * once, and two callers cannot arrive at different answers.
 *
 * @param expectedCurrentPath where the caller believes the file is now. Checked
 * rather than trusted: it is what turns a decision made from a stale reading
 * into a refusal instead of into a wrong write
 * @param eventId identity of this change, and what makes a retry safe. The same
 * attempt made twice carries the same one and is recorded once; two different
 * changes carrying one identity is an error rather than an overwrite. A caller
 * acting on an operation of ours reserves it before touching the disk - it is on
 * the movement the operation prepared - so a retry brings back the identity its
 * first attempt was going to use. A caller reporting something it merely
 * observed has no operation to have reserved anything and mints one
 * @param provenance when it happened, what observed it, what proves it and the
 * identity behind that proof - one object because they are one answer, and
 * because the door takes them as one
 */
public record LocationChange(Long catalogFileId, UUID eventId, Path expectedCurrentPath, Path newPath,
		CatalogFactProvenance provenance) {
}