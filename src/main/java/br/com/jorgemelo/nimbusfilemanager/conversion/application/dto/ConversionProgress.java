package br.com.jorgemelo.nimbusfilemanager.conversion.application.dto;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.EtaEstimate;

/**
 * Snapshot the screen polls while a batch runs: how many files are done, how
 * far into the current one ffmpeg is, and - once {@code running} turns false -
 * the final report.
 */
public record ConversionProgress(boolean running, int processed, int total, double percent, int filePercent,
		EtaEstimate eta, String currentFile, ConversionResult result) {
}