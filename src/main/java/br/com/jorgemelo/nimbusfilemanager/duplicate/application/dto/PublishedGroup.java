package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

import java.util.List;

/**
 * One group of a published analysis, ready to render.
 *
 * @param similarityPercent as computed by the analysis. Complete linkage
 * guarantees it is at or above that analysis's threshold - every pair inside a
 * group was compared and passed - so it is never a sentinel and never below what
 * was asked for
 * @param actionableMembers how many of its files can still be acted on, so the
 * screen can tell a group that is fully usable from one whose files have since
 * been deleted or quarantined
 */
public record PublishedGroup(String groupId, int similarityPercent, long wastedBytes, List<PublishedMember> members,
		int actionableMembers) {
}