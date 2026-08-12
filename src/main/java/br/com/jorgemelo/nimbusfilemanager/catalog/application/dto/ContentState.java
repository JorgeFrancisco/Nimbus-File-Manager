package br.com.jorgemelo.nimbusfilemanager.catalog.application.dto;

import java.time.Instant;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.FilesystemIdentity;

/**
 * What is known about a file's bytes at one moment - used both for what the
 * catalog holds and for what was just seen, because the comparison is between
 * two answers to the same question.
 *
 * @param sha256 the digest, or null when nobody has computed one. Null on the
 * known side means there is nothing to differ from; null on the observed side
 * means the look was a cheap one
 * @param identity the physical object, or null when the source could not name
 * one. It answers whether this is the same thing, which is a different question
 * from whether it holds the same bytes
 */
public record ContentState(String sha256, Long sizeBytes, Instant modifiedAt, FilesystemIdentity identity) {
}