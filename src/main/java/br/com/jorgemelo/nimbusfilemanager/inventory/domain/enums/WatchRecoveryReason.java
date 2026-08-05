package br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums;

/**
 * Why a change source is asking for the catalog to be brought back in line with
 * the tree.
 *
 * <p>
 * Every value asks for the same recovery, and that is exactly why they have to
 * be told apart. Collapsing them into one boolean named "overflow" is what made
 * an ordinary startup - a journal cursor too old to replay, which is the normal
 * state after any long run - be reported as "the change source reported an
 * overflow": a sentence that says events were lost when none were. A day was
 * spent reading the wrong half of the watcher because of it.
 */
public enum WatchRecoveryReason {

	/**
	 * The operating system dropped change notifications: something really did
	 * happen and this process will never be told what. The only honest answer is
	 * to look at the tree again.
	 */
	EVENTS_LOST,

	/**
	 * The USN journal could not be replayed from the stored cursor - there was
	 * none, the journal was recreated, or the cursor has aged out of it. Nothing
	 * was lost while the application was running; what is unknown is what happened
	 * while it was not.
	 */
	JOURNAL_UNREPLAYABLE,

	/**
	 * The replay started but could not account for every record in the window it
	 * covered. Narrower than {@link #JOURNAL_UNREPLAYABLE}: the cursor was valid
	 * and the read began, and it is the completeness of that read that is in
	 * doubt.
	 */
	JOURNAL_REPLAY_INCOMPLETE
}