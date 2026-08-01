package br.com.jorgemelo.nimbusfilemanager.backup.application.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * What a backup file says about itself. The schema version is the field that
 * matters: data taken from one schema and loaded into another lands in columns
 * that moved or vanished, and that is how a backup turns into corruption
 * instead of a rescue. The restore compares it and refuses on a mismatch.
 */
public record BackupManifest(String schemaVersion, String applicationVersion, LocalDateTime createdAt,
		List<String> tables) {
}