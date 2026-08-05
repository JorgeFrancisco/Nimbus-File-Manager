package br.com.jorgemelo.nimbusfilemanager.inventory.application.dto;

import java.nio.file.Path;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataOptions;

/**
 * Everything one inventory pass needs to know, resolved once when it starts.
 *
 * <p>
 * These used to be Spring Batch job parameters - a string map, read back
 * through {@code @Value("#{jobParameters['...']}")} into step-scoped beans, so
 * every argument was a string and every reader had to parse it again. As a
 * record they are typed, they travel by ordinary argument, and nothing has to
 * be a bean to receive them.
 *
 * @param sourcePath the folder being walked, already normalised
 * @param scanOptions what the walk includes and skips
 * @param metadataOptions what the extraction computes for each file
 */
public record InventoryScanRequest(Path sourcePath, ScanOptions scanOptions, MetadataOptions metadataOptions) {
}