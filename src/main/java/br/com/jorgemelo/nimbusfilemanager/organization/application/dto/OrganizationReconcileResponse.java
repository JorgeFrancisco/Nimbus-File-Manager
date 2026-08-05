package br.com.jorgemelo.nimbusfilemanager.organization.application.dto;

import java.util.List;

public record OrganizationReconcileResponse(String sourcePath, boolean recursive, boolean includeHidden,
		long filesOnDisk, long filesInDatabase, long missingOnDisk, long missingInDatabase, long pathMismatches,
		List<OrganizationReconcileIssueResponse> missingOnDiskSamples,
		List<OrganizationReconcileIssueResponse> missingInDatabaseSamples,
		List<OrganizationReconcileIssueResponse> pathMismatchSamples, long renamed, long repairedPaths,
		long markedMissing, long repairedItems) {

	/**
	 * No-op result returned when reconcile was deferred because another critical
	 * operation held the lock: nothing on disk or in the catalog was inspected or
	 * changed.
	 */
	public static OrganizationReconcileResponse deferred(String sourcePath) {
		return new OrganizationReconcileResponse(sourcePath, false, false, 0, 0, 0, 0, 0, List.of(), List.of(),
				List.of(), 0, 0, 0, 0);
	}
}