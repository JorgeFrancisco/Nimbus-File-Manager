package br.com.jorgemelo.nimbusfilemanager.metadata.application.dto;

import java.time.LocalDateTime;

/**
 * One capture date a simulated rebuild would change, as the screen shows it:
 * what the catalog holds today and what the run would write instead. The source
 * arrives worded, so the browser never turns a code into a sentence.
 */
public record MetadataRebuildPreview(String path, LocalDateTime currentDate, String currentSourceLabel,
		LocalDateTime newDate, String newSourceLabel) {
}