package br.com.jorgemelo.nimbusfilemanager.execution.application.dto;

/**
 * What a worker learns when it takes a job off the queue: enough to know what
 * to run and which paths to lock, and nothing more. The rest of the row - the
 * counters, the message, the history - belongs to whoever reports progress, and
 * reading it here would mean loading an entity the claim does not need.
 *
 * @param requestPayload the arguments as stored JSON, or {@code null} for the
 * types whose arguments are already columns of their own
 */
public record ClaimedExecution(long id, String executionType, String sourcePath, String targetPath,
		String requestPayload) {
}