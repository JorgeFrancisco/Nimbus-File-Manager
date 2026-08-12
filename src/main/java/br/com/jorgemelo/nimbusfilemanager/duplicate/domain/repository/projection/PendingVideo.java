package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection;

/**
 * A video still needing a fingerprint: its id, the path to read and the
 * duration used to place the frame samples. Produced by the derived pending
 * query and consumed by the backlog worker; it carries no state, so a crash
 * just re-derives it.
 */
public record PendingVideo(Long catalogFileId, String path, Double durationSeconds, Long contentRevision) {
}