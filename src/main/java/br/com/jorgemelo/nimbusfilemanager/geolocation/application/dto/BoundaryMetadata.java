package br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Content of workspace/geodata/metadata.json - the only persistent record of
 * which boundary dataset is installed. Technology-neutral: it stores a provider
 * label, not a hard-coded source name.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BoundaryMetadata {

	private String provider;
	private String license;
	private String version;
	private long importedRecords;
	private long sizeBytes;
	private LocalDateTime downloadedAt;
	private LocalDateTime importedAt;
	private String lastError;
}