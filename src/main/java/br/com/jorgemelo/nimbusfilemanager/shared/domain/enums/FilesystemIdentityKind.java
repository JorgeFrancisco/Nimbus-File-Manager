package br.com.jorgemelo.nimbusfilemanager.shared.domain.enums;

/**
 * What kind of thing a filesystem identity value is.
 *
 * <p>
 * The platforms do not mean the same thing by "the same object", so values are
 * not comparable across kinds and this says which one is in hand. Only kinds
 * something actually produces are listed: an identity nobody can supply would
 * be a promise the code cannot keep.
 */
public enum FilesystemIdentityKind {

	/**
	 * The 64-bit file id Windows reports for a file on a volume that keeps one -
	 * on NTFS, 16 bits of sequence number over 48 bits of MFT record index.
	 *
	 * <p>
	 * One kind for both producers because they were measured to be the same
	 * number, not because both are called an id: for one file, the USN journal's
	 * {@code FileReferenceNumber}, {@code ReadDirectoryChangesExW}'s
	 * {@code FileId} and what the OS reports for that path are byte-identical.
	 *
	 * <p>
	 * The sequence half is what makes this safe. A deleted file's MFT record is
	 * handed to the next file created, so the index alone would say the two are
	 * the same object; the sequence number increments on reuse, and the pair does
	 * not repeat. Deliberately not the 128-bit form a ReFS volume issues - that
	 * is a different width and would be a different kind, and nothing here reads
	 * it.
	 */
	WINDOWS_FILE_ID
}