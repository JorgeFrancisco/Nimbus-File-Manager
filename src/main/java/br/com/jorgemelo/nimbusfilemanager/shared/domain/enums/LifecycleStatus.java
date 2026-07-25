package br.com.jorgemelo.nimbusfilemanager.shared.domain.enums;

/**
 * Lifecycle state of a {@code catalog_file}, replacing the former pair of
 * booleans {@code exists_flag} + {@code deleted} with a single,
 * mutually-exclusive status. Invariants:
 *
 * <ul>
 * <li>{@link #ACTIVE} - present on disk and not removed (former
 * {@code exists_flag=true, deleted=false}). The only state screens list.</li>
 * <li>{@link #MISSING} - reconcile found the file absent from disk, but it was
 * not explicitly removed (former {@code exists_flag=false, deleted=false}). A
 * later inventory that finds it again promotes it back to ACTIVE.</li>
 * <li>{@link #DELETED} - explicitly removed (former {@code deleted=true}). Wins
 * over MISSING: a DELETED file is never downgraded to MISSING.</li>
 * </ul>
 */
public enum LifecycleStatus {

	ACTIVE, MISSING, DELETED
}