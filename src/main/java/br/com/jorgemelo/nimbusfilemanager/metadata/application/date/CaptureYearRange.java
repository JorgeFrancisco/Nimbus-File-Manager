package br.com.jorgemelo.nimbusfilemanager.metadata.application.date;

import java.time.Clock;
import java.time.Year;

/**
 * Shared plausibility guard for a parsed capture year: a real photo/video year
 * is between 1900 and next year (a small allowance for clock skew), so anything
 * outside that range is a mis-parse and must be rejected. Centralised so the
 * folder-layout resolver and the filename rules apply the exact same bounds
 * instead of each hardcoding 1900.
 */
public final class CaptureYearRange {

	private static final int EARLIEST_YEAR = 1900;

	private CaptureYearRange() {
	}

	public static boolean isPlausible(int year, Clock clock) {
		return year >= EARLIEST_YEAR && year <= Year.now(clock).getValue() + 1;
	}
}