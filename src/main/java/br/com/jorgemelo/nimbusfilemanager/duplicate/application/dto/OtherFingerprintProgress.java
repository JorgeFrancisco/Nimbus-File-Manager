package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

/**
 * The other medium's fingerprint, as a similarity tab shows it: a name and how
 * far along it is.
 *
 * <p>
 * It carries a label rather than a type because the screen never translates a
 * domain concept - the sentence arrives in the reader's language, and the tab
 * only draws it. And it carries a percentage rather than the four counters
 * because this is context, not the tab's own panel: the point is that something
 * else is under way and roughly how far, not to turn every tab into a dashboard
 * of both queues.
 *
 * @param label already localized, naming which fingerprint is running
 * @param percent 0-100 completion of that fingerprint
 */
public record OtherFingerprintProgress(String label, double percent) {
}