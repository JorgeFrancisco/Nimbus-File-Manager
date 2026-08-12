package br.com.jorgemelo.nimbusfilemanager.organization.application.dto;

import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentSuspect;

/**
 * What one comparison of the catalog against the disk found.
 *
 * <p>
 * The response carries the counts and the samples a screen shows; the three
 * lists are the whole of what the pass has to act on. They are separate because
 * they are different things and were once the same: the samples stop at a
 * hundred, and a repair that read them repaired a hundred of five thousand
 * differences.
 *
 * @param missingFiles every catalogued file the walk did not find, with the
 * place the catalog last had it
 * @param physicalOnly every path on disk the catalog does not know
 * @param contentSuspects every present file whose cheap facts moved, which asks
 * for a reading rather than concluding one, with the place it sits
 */
public record Scan(OrganizationReconcileResponse response, List<MissingFile> missingFiles,
		List<String> physicalOnly, List<ContentSuspect> contentSuspects) {
}