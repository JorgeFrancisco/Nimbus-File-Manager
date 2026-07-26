package br.com.jorgemelo.nimbusfilemanager.conversion.application.dto;

import java.util.List;
import java.util.UUID;

/**
 * The whole batch: how many files were converted, skipped and failed, how much
 * disk the conversion actually gave back, and the execution the history screen
 * can open. {@code configured} is false only when quarantining the originals
 * was asked for without a quarantine folder configured - nothing runs then.
 */
public record ConversionResult(boolean configured, int total, int converted, int skipped, int errors,
		long originalBytes, long convertedBytes, long savedBytes, String savedLabel, int savedPercent,
		UUID executionId, String message, List<ConversionFileResult> items) {

	public static ConversionResult refused(String message) {
		return new ConversionResult(false, 0, 0, 0, 0, 0, 0, 0, null, 0, null, message, List.of());
	}

	public static ConversionResult empty(String message) {
		return new ConversionResult(true, 0, 0, 0, 0, 0, 0, 0, null, 0, null, message, List.of());
	}
}