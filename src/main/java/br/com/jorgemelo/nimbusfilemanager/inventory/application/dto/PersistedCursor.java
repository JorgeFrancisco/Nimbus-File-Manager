package br.com.jorgemelo.nimbusfilemanager.inventory.application.dto;

/**
 * A previously persisted USN journal cursor for a volume: the journal id (a
 * change means the journal was recreated and the cursor is invalid) and the
 * next USN to resume reading from.
 */
public record PersistedCursor(long journalId, long nextUsn) {
}