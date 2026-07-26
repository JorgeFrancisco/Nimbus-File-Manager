package br.com.jorgemelo.nimbusfilemanager.conversion.application.dto;

/**
 * The counters of a finished batch, in the shape both the execution record and
 * the screen report need.
 */
public record ConversionTotals(int total, int converted, int skipped, int errors, long originalBytes,
		long convertedBytes, long savedBytes) {
}