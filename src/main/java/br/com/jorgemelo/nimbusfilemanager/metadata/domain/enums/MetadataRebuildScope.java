package br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums;

/**
 * What a rebuild run covers. A folder larger than the per-run ceiling takes
 * more than one run, so the screen has to say whether this one continues the
 * previous one or starts over.
 */
public enum MetadataRebuildScope {

	/**
	 * Only files the previous run did not reach, by skipping whatever was analysed
	 * since it started. Repeated runs walk the whole folder, and a file added or
	 * changed after that instant comes along on the next one.
	 */
	CONTINUE,

	/**
	 * Every file in the folder, ignoring what has already been rebuilt - the honest
	 * choice after a classification rule changes, when the previous pass is exactly
	 * what has to be redone.
	 */
	ALL
}