package br.com.jorgemelo.nimbusfilemanager.organization.application.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationConflictType;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

public record OrganizationItem(@JsonIgnore Long internalCatalogFileId, UUID catalogFileId, String fileName,
		String sourcePath, String targetPath, String yearMonth, String day, String category, String subcategory,
		String fileType, String rule, String matchReason, Long sizeBytes, boolean samePath, boolean missingDate,
		boolean targetExists, boolean duplicateTarget, boolean conflict, String conflictType, String location,
		String locationConfidence) {

	public OrganizationItem(Long catalogFileId, String fileName, String sourcePath, String targetPath, String yearMonth,
			String day, String category, String subcategory, String fileType, String rule, String matchReason,
			Long sizeBytes, boolean samePath, boolean missingDate, boolean targetExists, boolean duplicateTarget,
			boolean conflict, String conflictType) {
		this(catalogFileId, UuidV7.fromLegacy(catalogFileId), fileName, sourcePath, targetPath, yearMonth, day,
				category,
				subcategory, fileType, rule, matchReason, sizeBytes, samePath, missingDate, targetExists,
				duplicateTarget, conflict, conflictType, null, null);
	}

	public OrganizationItem withConflict(boolean targetExists, boolean duplicateTarget) {
		boolean conflict = targetExists || duplicateTarget;

		OrganizationConflictType conflictType = resolveConflictType(targetExists, duplicateTarget);

		return new OrganizationItem(internalCatalogFileId, catalogFileId, fileName, sourcePath, targetPath, yearMonth,
				day,
				category, subcategory, fileType, rule, matchReason, sizeBytes, samePath, missingDate, targetExists,
				duplicateTarget, conflict, conflictType == null ? null : conflictType.name(), location,
				locationConfidence);
	}

	private static OrganizationConflictType resolveConflictType(boolean targetExists, boolean duplicateTarget) {
		if (targetExists && duplicateTarget) {
			return OrganizationConflictType.TARGET_EXISTS_AND_DUPLICATE;
		}

		if (targetExists) {
			return OrganizationConflictType.TARGET_EXISTS;
		}

		if (duplicateTarget) {
			return OrganizationConflictType.DUPLICATE_TARGET;
		}

		return null;
	}
}