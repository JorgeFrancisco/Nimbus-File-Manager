package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

import java.util.List;

/**
 * One cluster the analysis found, in the order it decided.
 *
 * <p>
 * The keep is the first member: the assembler already ranks them, and freezing
 * that order is what lets the screen render a group without re-running the keep
 * policy over files whose quality data may have changed since.
 *
 * @param similarityPercent the lowest pairwise score inside the group, which the
 * complete-linkage rule guarantees is at or above the threshold of the analysis
 */
public record AnalyzedGroup(int similarityPercent, long wastedBytes, List<AnalyzedMember> members) {
}