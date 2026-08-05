package br.com.jorgemelo.nimbusfilemanager.conversion.application.dto;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * What stays the same for every file of one conversion batch.
 *
 * <p>
 * The row it runs under, the ownership of the paths it locked, the way to ask
 * whether somebody cancelled, where originals go and how to encode: five things
 * decided once and carried unchanged through each file, which is what makes
 * them one argument instead of five repeated at every call.
 *
 * @param quarantineRoot where the original goes, or {@code null} to keep it
 */
public record ConversionRun(Execution execution, ExecutionOwnership ownership, BooleanSupplier cancelled,
		Path quarantineRoot, ConversionOptions options) {
}