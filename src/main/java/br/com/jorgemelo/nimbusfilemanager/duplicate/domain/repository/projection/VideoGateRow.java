package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection;

/**
 * What the cheap video gates need, and nothing else: one row per fingerprinted
 * video with its duration and display size.
 *
 * <p>
 * An incremental run compares the arrivals against every video already
 * incorporated, and the duration and aspect gates reject the overwhelming
 * majority of those pairs before a single frame is read - measured at 4% of all
 * pairs surviving on a real library. The frames are 5 KB per video and this is
 * about twenty bytes, so asking the gates first and fetching frames only for the
 * pairs that survive is the difference between reading a few megabytes and
 * reading half a gigabyte at a hundred thousand videos.
 *
 * <p>
 * No lifecycle filter and no id list, deliberately, for the reason its photo
 * counterpart has none: the covered set is the size of the library, so it cannot
 * be an {@code IN} list, and a covered file hidden today is still part of the
 * relation universe - filtering it out would leave the pair between it and the
 * newcomer evaluated by nobody.
 */
public interface VideoGateRow {

	Long getCatalogFileId();

	Double getDurationSeconds();

	Integer getDisplayWidth();

	Integer getDisplayHeight();
}