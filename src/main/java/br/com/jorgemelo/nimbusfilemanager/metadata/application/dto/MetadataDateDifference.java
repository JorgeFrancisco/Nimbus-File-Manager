package br.com.jorgemelo.nimbusfilemanager.metadata.application.dto;

import java.time.LocalDateTime;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;

/**
 * One capture date a dry run would change, as the run found it: both sources as
 * the enum, because this is what gets stored.
 *
 * <p>
 * The worded version the screen shows is {@link MetadataRebuildPreview}, built
 * when the row is read. Keeping the two apart is what stops the language of
 * whoever happened to run the simulation from being the language it is read in.
 */
public record MetadataDateDifference(String path, LocalDateTime currentDate, DateSource currentSource,
		LocalDateTime newDate, DateSource newSource) {
}