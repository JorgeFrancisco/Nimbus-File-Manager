package br.com.jorgemelo.nimbusfilemanager.media.domain.enums;

/**
 * The two ways the explorer removes an entry: {@code QUARANTINE} moves it to
 * the quarantine area, where a restore can bring it back, and
 * {@code PERMANENT} deletes it from disk with no way back. The dialog offers
 * both on purpose, so the destructive one is always a deliberate second choice.
 */
public enum ExplorerDeleteMode {

	QUARANTINE, PERMANENT
}