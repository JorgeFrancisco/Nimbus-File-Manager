package br.com.jorgemelo.nimbusfilemanager.statistics.application.dto;

import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SizeResponse;

/**
 * Files of one codec, with the ones no longer on disk counted apart. Summing
 * them into a single number told the user a codec had files the library cannot
 * open - and made the conversion screen look as if it were hiding work.
 */
public record CodecStatisticsResponse(String codec, long files, long missing, double percentage,
		SizeResponse totalSize) {
}