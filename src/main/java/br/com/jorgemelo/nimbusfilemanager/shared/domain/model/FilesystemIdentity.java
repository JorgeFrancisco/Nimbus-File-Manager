package br.com.jorgemelo.nimbusfilemanager.shared.domain.model;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FilesystemIdentityKind;

/**
 * The operating system's own answer to "is this the same object as before",
 * inside the one place where it is entitled to answer.
 *
 * <p>
 * Three parts, none of which stands alone. The kind says what the value means,
 * because an NTFS file reference and a POSIX inode are different claims. The
 * scope says where it is unique, because the same number names a different file
 * on the next volume - an identity without a scope is not an identity, which is
 * why the constructor refuses one. The value is the number itself, kept as text
 * because what the platforms hand out does not share a numeric type: an NTFS
 * reference is an unsigned 64-bit that overflows a {@code long}.
 *
 * <p>
 * What this is not, deliberately: the identity of a catalogued file - that is
 * {@code catalog_file_public_id}, and it survives things this does not, such as
 * a copy-and-delete or a move to another volume. Nor is it a statement about
 * content: two files with identical bytes have different identities, and a file
 * rewritten in place keeps the one it had.
 *
 * @param kind what the value means, and therefore what it may be compared to.
 * @param scope where the value is unique - for NTFS, the volume.
 * @param value the platform's identifier, as text.
 */
public record FilesystemIdentity(FilesystemIdentityKind kind, String scope, String value) {

	public FilesystemIdentity {
		if (kind == null || scope == null || scope.isBlank() || value == null || value.isBlank()) {
			throw new IllegalArgumentException("A filesystem identity needs a kind, a scope and a value");
		}
	}

	/**
	 * @param volumeScope the volume the id belongs to, without which the number
	 * means nothing.
	 * @param fileId the Windows file id, read as unsigned - it is a
	 * {@code DWORDLONG} whose top bits carry the sequence number, so the high
	 * values really do occur.
	 */
	public static FilesystemIdentity windowsFileId(String volumeScope, long fileId) {
		return new FilesystemIdentity(FilesystemIdentityKind.WINDOWS_FILE_ID, volumeScope,
				Long.toUnsignedString(fileId));
	}
}