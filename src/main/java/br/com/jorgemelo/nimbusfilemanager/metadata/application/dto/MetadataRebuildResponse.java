package br.com.jorgemelo.nimbusfilemanager.metadata.application.dto;

/**
 * @param simulation what a dry run found, or {@code null} for a real run.
 */
public record MetadataRebuildResponse(String sourcePath, boolean dryRun, int candidates, int rebuilt,
		int skippedMissing, int skippedWithoutLocation, int skippedUnsupportedType, int errors,
		MetadataRebuildSimulation simulation) {
}