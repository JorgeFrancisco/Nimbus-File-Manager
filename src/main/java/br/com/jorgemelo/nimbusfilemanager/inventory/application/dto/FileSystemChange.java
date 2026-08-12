package br.com.jorgemelo.nimbusfilemanager.inventory.application.dto;

import java.nio.file.Path;
import java.time.Instant;

import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.FileChangeKind;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.FileChangeSourceKind;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.FilesystemIdentity;

/**
 * One thing a file-system source observed, with everything that source knew
 * about it.
 *
 * <p>
 * What the sources return instead of a bare path. They are not equally
 * informed - the USN journal knows the object's identity and pairs both halves
 * of a rename, {@code ReadDirectoryChangesW} pairs the rename but knows no
 * identity, {@code WatchService} does neither - and a contract of paths
 * flattened all three to the least of them at the boundary, which is where the
 * information was being lost. It was not the operating system that was silent.
 *
 * <p>
 * What a source does not know is null and stays null. Nothing here is inferred
 * to fill a gap: the consumer that wants more than the source gave has to go
 * and pay for it, and should be able to see that it is paying.
 *
 * @param kind what happened to the entry.
 * @param path where the entry is now; for a rename, the destination.
 * @param previousPath where it was, for {@link FileChangeKind#RENAMED} only -
 * null for every other kind.
 * @param identity the operating system's own name for the object, or null when
 * the source cannot supply one.
 * @param source which mechanism observed this, and therefore how much it knew.
 * @param directory whether the entry is a directory, which is what decides
 * between relocating one file and relocating a whole subtree. Best effort on a
 * source that carries no attributes, because an entry already gone from disk
 * cannot be asked what it was.
 * @param occurredAt the instant the source itself assigns to the change, or
 * null when it assigns none. Only the journal does: it replays a window the
 * application was absent for, so what it says is the difference between a fact
 * dated when it happened and one dated when it was found. A live notification
 * carries no time at all, and nothing here invents one - what fills the gap is
 * the clock at the point the fact is written, and it is that point's business
 * to say so.
 */
public record FileSystemChange(FileChangeKind kind, Path path, Path previousPath, FilesystemIdentity identity,
		FileChangeSourceKind source, boolean directory, Instant occurredAt) {
}