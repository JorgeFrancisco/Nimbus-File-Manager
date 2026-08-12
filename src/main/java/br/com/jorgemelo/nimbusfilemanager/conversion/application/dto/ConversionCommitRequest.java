package br.com.jorgemelo.nimbusfilemanager.conversion.application.dto;

import java.nio.file.Path;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.ResolvedMediaDate;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * What a finished encode needs in order to enter the library: the run it belongs
 * to, the file it replaces, the output waiting in the workspace, and the two
 * decisions the commit has to honour.
 *
 * <p>
 * They travel together because the commit is one step and they are one
 * description of it - four of the six are paths and objects of the same file,
 * and a signature listing them separately is one where two arguments of the same
 * type sit side by side.
 *
 * @param converted the encode's output, still in the workspace
 * @param quarantineRoot where the original goes, or {@code null} to keep it
 * @param originalDate the capture date the replaced file had, so a conversion
 * written today does not date decade-old footage as today
 */
public record ConversionCommitRequest(Execution execution, CatalogFile file, Path converted, Path quarantineRoot,
		ConversionOptions options, ResolvedMediaDate originalDate) {
}