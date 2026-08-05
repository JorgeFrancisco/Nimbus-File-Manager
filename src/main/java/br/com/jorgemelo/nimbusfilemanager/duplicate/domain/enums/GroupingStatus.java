package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums;

/**
 * The life of a durable similarity result.
 *
 * <ul>
 * <li>{@code BUILDING} - a worker is filling it in. Never read by a screen: a
 * half-written grouping shows groups whose missing half would have changed
 * them.</li>
 * <li>{@code ACTIVE} - published, and the one answer to its family. It stays
 * readable while a newer analysis is being computed, and while the library
 * moves on underneath it: a result is not wrong because the world gained a
 * photo, it is just about the world it was computed from.</li>
 * <li>{@code SUPERSEDED} - a successor was published in the same transaction
 * that retired this one. Kept for as long as retention says, deleted without
 * anybody losing an answer.</li>
 * </ul>
 */
public enum GroupingStatus {

	BUILDING, ACTIVE, SUPERSEDED
}