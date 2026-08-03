package br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.projection;

/**
 * One row of the post-move integrity report: how many movements ended with a
 * given (status, reason) pair for an execution. {@code reason} is null for a
 * plain successful MOVED. Rendered on the execution detail screen so the
 * outcome of a run is visible at a glance (moved, skipped-by-reason, errors,
 * integrity failures) without scrolling the full movement list.
 */
public record MovementSummaryResponse(String status, String reason, long count) {
}