package br.com.jorgemelo.nimbusfilemanager.organization.domain.enums;

/**
 * The three states a stored plan can be in.
 *
 * <p>
 * Only {@code READY} is ever read. A plan being written is invisible, so the
 * screen can never show half of one, and a plan that failed mid-write stays
 * identifiable rather than silently looking like a plan with fewer items than
 * the run actually found.
 */
public enum PlanStatus {

	BUILDING, READY, FAILED
}