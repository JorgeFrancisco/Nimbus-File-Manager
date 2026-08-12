package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection;

/**
 * A photo still needing a fingerprint (id + the path to read). Produced by the
 * derived pending query and consumed by the backlog worker; it carries no
 * state, so a crash just re-derives it.
 */
public record PendingPhoto(Long catalogFileId, String path, Long contentRevision) {
}