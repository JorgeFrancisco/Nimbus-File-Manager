package br.com.jorgemelo.nimbusfilemanager.conversion.application.dto;

import java.nio.file.Path;

import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionFailure;

/**
 * What happened after a successful encode: where the converted file ended up,
 * whether the original went to quarantine, and what (if anything) went wrong
 * along the way.
 *
 * <p>
 * The two are independent on purpose. Once the converted file is in the
 * library, the conversion itself succeeded - a later problem (the original
 * could not be quarantined, the catalog write failed) is reported alongside the
 * new file instead of pretending the encode did not happen, which is why
 * {@code converted} and {@code failure} can both be present.
 */
public record CommitResult(Path converted, boolean originalQuarantined, ConversionFailure failure) {

	public static CommitResult committed(Path converted, boolean originalQuarantined) {
		return new CommitResult(converted, originalQuarantined, null);
	}

	/** The converted file is in the library, but a follow-up step did not run. */
	public static CommitResult partial(Path converted, boolean originalQuarantined, ConversionFailure failure) {
		return new CommitResult(converted, originalQuarantined, failure);
	}

	public static CommitResult failed(ConversionFailure failure) {
		return new CommitResult(null, false, failure);
	}

	/** True once the converted file is in the library, warnings aside. */
	public boolean successful() {
		return converted != null;
	}
}