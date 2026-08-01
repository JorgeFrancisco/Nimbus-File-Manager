package br.com.jorgemelo.nimbusfilemanager.backup.application.dto;

import java.time.LocalDateTime;

/** One backup on disk, as the settings screen lists it. */
public record BackupFile(String name, long sizeBytes, LocalDateTime createdAt) {
}