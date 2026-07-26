package br.com.jorgemelo.nimbusfilemanager.conversion.application.dto;

import java.util.List;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.AudioHandling;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionQuality;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.NameAffixPosition;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.OriginalDisposition;

/** What the Conversão screen posts when the user starts a batch. */
public record ConversionRequest(List<UUID> ids, ConversionQuality quality, AudioHandling audio,
		OriginalDisposition disposition, String nameAffix, NameAffixPosition affixPosition) {
}