package br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto;

import java.util.Map;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExternalToolCategory;

public record Snapshot(long tasksExecuted, long tasksCacheAvoided, long tasksCancelled, long tasksError,
		long queueWaitNanos, long taskTotalNanos, long wallClockNanos, int maxConcurrency,
		Map<ExternalToolCategory, CategorySnapshot> categories) {
}