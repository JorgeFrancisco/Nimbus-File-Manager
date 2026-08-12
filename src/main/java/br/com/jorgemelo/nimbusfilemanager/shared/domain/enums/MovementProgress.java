package br.com.jorgemelo.nimbusfilemanager.shared.domain.enums;

/**
 * How far an operation on record has got, read from the operation itself and
 * from the disk together.
 *
 * <p>
 * Neither answers it alone. The file system cannot tell a move that was
 * interrupted from one that finished - both leave the source gone and the
 * destination there - and the operation cannot tell a pending one that has not
 * started from a pending one whose worker died holding it. The pair can.
 */
public enum MovementProgress {

	/** Nothing has happened yet: carry it out. */
	EXECUTE,

	/**
	 * The effect happened and was never recorded. Finish it where it stopped -
	 * under the identities reserved before any of it - rather than doing it twice.
	 */
	RESUME,

	/** Already carried out and recorded. There is nothing left to do. */
	ALREADY_DONE,

	/**
	 * The state cannot be read as a legitimate attempt at this operation: it was
	 * settled as something other than a move, or neither end of it is on disk.
	 * Deciding anything here would be guessing.
	 */
	REFUSE
}