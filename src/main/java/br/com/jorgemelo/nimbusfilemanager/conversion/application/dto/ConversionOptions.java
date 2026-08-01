package br.com.jorgemelo.nimbusfilemanager.conversion.application.dto;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.constants.ConversionConstants;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.AudioHandling;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionQuality;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.NameAffixPosition;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.OriginalDisposition;

/**
 * The choices the Conversão screen offers, already validated. Anything missing
 * falls back to the recommended combination, so a request can never convert
 * under an option the user did not pick.
 *
 * @param nameAffix text the user wants in the converted file's name, or empty
 * to keep the source name. Raw here - the naming layer is what strips whatever
 * a file name cannot hold
 */
public record ConversionOptions(ConversionQuality quality, AudioHandling audio, OriginalDisposition disposition,
		String nameAffix, NameAffixPosition affixPosition) {

	public static ConversionOptions defaults() {
		return new ConversionOptions(ConversionQuality.BALANCED, AudioHandling.AUTO, OriginalDisposition.KEEP,
				ConversionConstants.DEFAULT_NAME_AFFIX, NameAffixPosition.SUFFIX);
	}

	public static ConversionOptions of(ConversionQuality quality, AudioHandling audio, OriginalDisposition disposition,
			String nameAffix, NameAffixPosition affixPosition) {
		ConversionOptions defaults = defaults();

		return new ConversionOptions(quality == null ? defaults.quality() : quality,
				audio == null ? defaults.audio() : audio, disposition == null ? defaults.disposition() : disposition,
				nameAffix == null ? defaults.nameAffix() : nameAffix,
				affixPosition == null ? defaults.affixPosition() : affixPosition);
	}

	public boolean quarantinesOriginal() {
		return disposition == OriginalDisposition.QUARANTINE;
	}
}