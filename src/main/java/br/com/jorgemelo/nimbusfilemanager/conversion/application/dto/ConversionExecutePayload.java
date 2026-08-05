package br.com.jorgemelo.nimbusfilemanager.conversion.application.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.AudioHandling;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionQuality;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.NameAffixPosition;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.OriginalDisposition;

/**
 * Which videos to convert, and how.
 *
 * <p>
 * The videos travel here rather than in a column because a batch is a set the
 * user picked one by one - there is no folder that describes it. What the row
 * does carry is the folder they live in, which is what the worker locks; the
 * ids say which files inside it were asked for.
 *
 * <p>
 * Unknown fields are ignored: a batch queued by one version may be claimed by
 * the next, and a version that no longer knows an option has to run the request
 * rather than refuse it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConversionExecutePayload(Integer schemaVersion, List<UUID> publicIds, ConversionQuality quality,
		AudioHandling audio, OriginalDisposition disposition, String nameAffix, NameAffixPosition affixPosition) {
}