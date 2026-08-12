package br.com.jorgemelo.nimbusfilemanager.shared.application.dto;

import java.time.Instant;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.FilesystemIdentity;

/**
 * What an observer knows about a change it is reporting: when, who, on what
 * proof, and the identity behind that proof when there is one.
 *
 * <p>
 * One object rather than four arguments because they are one answer, asked
 * identically of the scalar door and the bulk one - and because a positional
 * list long enough to hold them is a place for two strings to be swapped
 * without anything noticing. It mirrors the {@code catalog_fact_provenance}
 * composite the database takes.
 *
 * @param evidence what proves the classification, from
 * {@code CatalogEventEvidence}
 * @param identity the filesystem identity behind the proof, or null when the
 * observer had none. An evidence that names the identity as its proof cannot
 * leave this null, and the database refuses that combination
 */
public record CatalogFactProvenance(Instant occurredAt, String source, String evidence,
		FilesystemIdentity identity) {
}