package br.com.jorgemelo.nimbusfilemanager.conversion.application.dto;

/**
 * Snapshot the screen polls while a batch runs: how many files are done, how
 * far into the current one ffmpeg is, and - once {@code running} turns false -
 * the final report.
 */
public record ConversionProgress(boolean running, int processed, int total, int percent, int filePercent,
		long etaSeconds, String currentFile, ConversionResult result) {
}