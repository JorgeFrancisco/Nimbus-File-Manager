package br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto;

/**
 * What to tell whoever asked for the records of absent files to be cleared.
 *
 * <p>
 * Four things can be true and the screen has to say a different sentence for
 * each: the request could not be made at all; nothing was absent, so nothing was
 * asked for; the clearing was queued and is still going; or it ended and this
 * many records are gone. The counter alone cannot tell them apart - three of the
 * four are zero - and a screen that reports "0 removed" to somebody who just
 * clicked a button has told them nothing about which of them happened.
 *
 * @param message already localized, because it is the sentence to show and not
 * a code the screen would have to know how to turn into one
 */
public record QuarantineCleanupResult(int removed, boolean pending, String message) {

	/**
	 * The request was not made, and this is why. Not the same as having found
	 * nothing: there, the operation ran and had no work; here, it could not be
	 * asked for at all.
	 */
	public static QuarantineCleanupResult refused(String message) {
		return new QuarantineCleanupResult(0, false, message);
	}

	public static QuarantineCleanupResult nothingToClear(String message) {
		return new QuarantineCleanupResult(0, false, message);
	}

	public static QuarantineCleanupResult queued(String message) {
		return new QuarantineCleanupResult(0, true, message);
	}

	public static QuarantineCleanupResult cleared(int removed, String message) {
		return new QuarantineCleanupResult(removed, false, message);
	}
}