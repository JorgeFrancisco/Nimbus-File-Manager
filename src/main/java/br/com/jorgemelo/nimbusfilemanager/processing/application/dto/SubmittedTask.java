package br.com.jorgemelo.nimbusfilemanager.processing.application.dto;

/**
 * One item on its way through the pool: which slot of the batch it belongs to,
 * what it is, and when it was handed over.
 *
 * <p>
 * The three travel together because they are one fact about one submission -
 * the slot is where its answer goes, and the instant is only meaningful against
 * the moment a worker picks it up, which is what the queue-wait metric is.
 * Passing them separately made the signature long enough to hide a
 * transposition between two of them.
 *
 * @param slot position in the batch, and therefore where the outcome is written
 * @param submittedAt {@code System.nanoTime()} at hand-over, for the wait a
 * worker measures when it starts
 */
public record SubmittedTask<I>(int slot, I item, long submittedAt) {
}