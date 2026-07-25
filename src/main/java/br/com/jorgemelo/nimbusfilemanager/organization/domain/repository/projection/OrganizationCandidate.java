package br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.projection;

import java.time.LocalDateTime;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

public record OrganizationCandidate(Long internalCatalogFileId, UUID catalogFileId, String fileName, String extension,
		FileType fileType, Long sizeBytes, String currentPath, String currentFolder, Integer year, Integer month,
		Integer day, String yearMonth, LocalDateTime captureDate, FileCategory category, MediaSubcategory subcategory) {

	public OrganizationCandidate(Long catalogFileId, String fileName, String extension, FileType fileType,
			Long sizeBytes,
			String currentPath, String currentFolder, Integer year, Integer month, Integer day, String yearMonth,
			LocalDateTime captureDate, FileCategory category, MediaSubcategory subcategory) {
		this(catalogFileId, UuidV7.fromLegacy(catalogFileId), fileName, extension, fileType, sizeBytes, currentPath,
				currentFolder, year, month, day, yearMonth, captureDate, category, subcategory);
	}
}