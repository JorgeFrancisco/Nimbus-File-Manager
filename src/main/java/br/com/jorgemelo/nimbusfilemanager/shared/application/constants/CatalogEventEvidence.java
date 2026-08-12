package br.com.jorgemelo.nimbusfilemanager.shared.application.constants;

/**
 * What proof stands behind a fact in the catalog's history.
 *
 * <p>
 * The third question a fact answers, and the one it could not answer before.
 * The other two are {@code event_type}, which says what happened, and
 * {@link CatalogEventSources}, which says who observed or produced it. This one
 * says how it is known - and it is a separate question because the same
 * observer will reach the same conclusion by different proofs, and different
 * observers will reach conclusions on the same proof.
 *
 * <p>
 * Strings and not an enum, for the reason {@link CatalogEventSources} gives:
 * the history outlives the code that wrote it, and a row recorded on a kind of
 * proof nothing offers any more has to stay readable.
 *
 * <p>
 * Only what something actually produces is named here. The proofs the operating
 * system offers - a rename it paired itself, a file id that proves one object
 * is the same object - are carried by every change the sources report since the
 * watcher stopped reducing them to paths, but nothing writes a fact from one
 * yet. They are named when the recognition that writes those facts exists, so
 * that a value in this class always means a fact somewhere can carry it.
 */
public final class CatalogEventEvidence {

	/**
	 * This application issued the operation and completed it, so what backs the
	 * fact is that we performed it rather than concluded it.
	 *
	 * <p>
	 * Not the same statement as the source, even where the two travel together
	 * today. Source names the capability that asked - the Files screen,
	 * organization, quarantine - and several of them share this proof; and the
	 * moment a capability both performs and observes, one source will carry more
	 * than one.
	 */
	public static final String NIMBUS_OPERATION = "NIMBUS_OPERATION";

	/**
	 * The filesystem's own identity for the object matched exactly one catalogued
	 * place, so the thing that moved is the thing the catalog already knows.
	 *
	 * <p>
	 * The strongest proof available, and preferred whenever it is present: a path
	 * can be reused by an unrelated file, and a sequence of bytes can be copied,
	 * but an identity that survives a rename and changes on a delete-and-recreate
	 * is a statement about the object rather than about its name or its contents.
	 */
	public static final String FILESYSTEM_IDENTITY_MATCH = "FILESYSTEM_IDENTITY_MATCH";

	/**
	 * The operating system reported both ends of one rename, and the path it said
	 * the file came from is the one the catalog had.
	 *
	 * <p>
	 * Weaker than an identity match and still a real proof: the pairing is the
	 * system's own, not an inference from a disappearance next to an arrival. Used
	 * when no usable identity was available to establish continuity - including
	 * the first change to a file whose identity the catalog had never recorded,
	 * which is how it comes to record one.
	 */
	public static final String OS_RENAME_PAIR = "OS_RENAME_PAIR";

	/**
	 * A directory the file was under was confirmed to have moved, and this fact is
	 * derived from that one.
	 *
	 * <p>
	 * Its own kind because neither of the others would be true of it. The
	 * operating system reported one rename, of the folder - it said nothing about
	 * this file, so calling it a rename pair would claim a pairing that was never
	 * observed. And the identity that may have come with it belongs to the folder:
	 * a directory's file id is not any of its children's, so recording an identity
	 * match here would attach one object's proof to another object's fact.
	 *
	 * <p>
	 * What it does say is exactly what happened: the file did not move, its
	 * address did, because something above it in the path did.
	 */
	public static final String ANCESTOR_RELOCATED = "ANCESTOR_RELOCATED";

	/**
	 * The digest read from the file disagrees with the digest the catalog held.
	 *
	 * <p>
	 * The only proof of a change of content there is. A notification saying a file
	 * was written is what sends somebody to look; it establishes nothing on its
	 * own, because the operating system reports a write whether or not the bytes
	 * ended up different - and neither a size nor a timestamp can tell the two
	 * apart. What is recorded here is the comparison, not the trigger.
	 */
	public static final String CONTENT_DIGEST_CHANGED = "CONTENT_DIGEST_CHANGED";

	/**
	 * The path the catalog said the file was at held nothing.
	 *
	 * <p>
	 * Absence, and only absence. It says the file was not where it was expected -
	 * not that it was deleted, not where it went, and not that it is gone for good.
	 * A file moved by something that was never observed produces exactly this
	 * evidence, and it is right to record it: what was established is that the
	 * catalog was looking in the wrong place.
	 */
	public static final String PATH_NOT_FOUND = "PATH_NOT_FOUND";

	/**
	 * There was a file at the place the catalog expected one.
	 *
	 * <p>
	 * The other half of {@link #PATH_NOT_FOUND}, and just as narrow: it says a
	 * walk met something where it was looking, not that the object it met is the
	 * one that left. Where the operating system can prove that, it does
	 * ({@link #FILESYSTEM_IDENTITY_MATCH}); this is what a walk on its own knows.
	 */
	public static final String PATH_FOUND = "PATH_FOUND";

	/**
	 * The bytes the catalog recorded for a file that vanished were found at
	 * exactly one place nobody had catalogued, and no other file the catalog holds
	 * has them.
	 *
	 * <p>
	 * Deliberately named for what was matched rather than for what was concluded.
	 * Identical bytes do not make two files one file - a copy has them too - so
	 * this is the weakest proof anything here writes a fact from, and it says so.
	 * What makes it usable is the sole part: one file lost them, one place gained
	 * them, and nothing else in the library claims them. Where the operating
	 * system tells us instead, its answer is stronger and is used
	 * ({@link #FILESYSTEM_IDENTITY_MATCH}, {@link #OS_RENAME_PAIR}); this is what
	 * is left when it cannot.
	 */
	public static final String SOLE_CONTENT_MATCH = "SOLE_CONTENT_MATCH";

	private CatalogEventEvidence() {
	}
}