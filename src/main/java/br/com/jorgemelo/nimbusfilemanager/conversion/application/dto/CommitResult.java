package br.com.jorgemelo.nimbusfilemanager.conversion.application.dto;

import java.nio.file.Path;

import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionFailure;

/**
 * What happened after a successful encode: where the converted file ended up,
 * whether the original went to quarantine, whether cataloguing the new file
 * brought a stale entry back to life, and what (if anything) went wrong along
 * the way.
 *
 * <p>
 * The two are independent on purpose. Once the converted file is in the
 * library, the conversion itself succeeded - a later problem (the original
 * could not be quarantined, the catalog write failed) is reported alongside the
 * new file instead of pretending the encode did not happen, which is why
 * {@code converted} and {@code failure} can both be present.
 *
 * <p>
 * {@code revivedCatalogEntry} is the narrow case of converting to a path the
 * catalog already knew and had given up on - the previous output of the same
 * conversion, deleted from outside the application and marked missing since.
 * Cataloguing the new file there makes that entry active again, which puts it
 * back in the set a duplicate analysis looks at. It travels here rather than in
 * the per-file report because the report is written to history, and an answer
 * that can only be true while the batch runs has no business being read back
 * from a row a week later.
 */
public record CommitResult(Path converted, boolean originalQuarantined, boolean revivedCatalogEntry,
		ConversionFailure failure) {

	public static CommitResult committed(Path converted, boolean originalQuarantined, boolean revivedCatalogEntry) {
		return new CommitResult(converted, originalQuarantined, revivedCatalogEntry, null);
	}

	/** The converted file is in the library, but a follow-up step did not run. */
	public static CommitResult partial(Path converted, boolean originalQuarantined, boolean revivedCatalogEntry,
			ConversionFailure failure) {
		return new CommitResult(converted, originalQuarantined, revivedCatalogEntry, failure);
	}

	public static CommitResult failed(ConversionFailure failure) {
		return new CommitResult(null, false, false, failure);
	}

	/** True once the converted file is in the library, warnings aside. */
	public boolean successful() {
		return converted != null;
	}
}