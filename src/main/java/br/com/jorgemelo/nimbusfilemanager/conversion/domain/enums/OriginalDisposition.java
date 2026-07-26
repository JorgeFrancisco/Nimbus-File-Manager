package br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums;

/**
 * What happens to the source file once the converted one is in place and
 * validated. Never applied when any step failed - a failed conversion always
 * leaves the original exactly where it was.
 */
public enum OriginalDisposition {

	/** Both files stay side by side; the user decides later. */
	KEEP,

	/**
	 * The original is soft-deleted into the shared quarantine, so it can still be
	 * restored from the Quarentena screen until the retention purge expunges it.
	 */
	QUARANTINE
}