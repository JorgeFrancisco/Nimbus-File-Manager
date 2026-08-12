package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.CatalogTimestamp;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.ContentChangeDetector;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.ContentVerificationLauncher;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentAssessment;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentState;
import br.com.jorgemelo.nimbusfilemanager.catalog.domain.enums.ContentVerdict;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.FileSystemChange;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.RecognizedLocation;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.FileChangeKind;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.LocationMatch;
import br.com.jorgemelo.nimbusfilemanager.shared.application.LocationChangeException;
import br.com.jorgemelo.nimbusfilemanager.shared.application.catalog.CatalogConvergenceMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.CatalogFactProvenance;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.LocationChange;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FilesystemIdentityKind;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.FilesystemIdentity;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.KnownContentRow;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.CatalogLocationWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;
import lombok.extern.slf4j.Slf4j;

/**
 * A file the catalog already knows turning up somewhere else, recognised from
 * what the operating system said rather than worked out afterwards.
 *
 * <p>
 * This is what the watcher used to be unable to do. A rename reached it as two
 * unrelated paths, so the only answer available was to walk the library and, for
 * anything that had gone missing, read and hash every new file on disk looking
 * for a pair with identical bytes - paying for a conclusion the system had
 * already reached and nobody had kept. Now the change arrives carrying both ends
 * and the object's own identity, and the answer is a lookup.
 *
 * <p>
 * <b>What it will not do.</b> It never invents continuity. A deletion is left to
 * the reconciliation, which is what decides lifecycle; a modification is left
 * alone, because what changed inside a file is not this question; and an
 * identity that names more than one place - a hard link is one object with two
 * names - settles nothing, so nothing is chosen. Each of those falls back to the
 * pass that was going to happen anyway, which is slower and complete.
 */
@Slf4j
@Service
public class FileChangeRecognition {

	private final CatalogFileLocationRepository catalogFileLocationRepository;
	private final CatalogLocationWriter catalogLocationWriter;
	private final CatalogConvergenceMutations catalogMutations;
	private final ContentChangeDetector contentChangeDetector;
	private final ContentVerificationLauncher contentVerificationLauncher;
	private final Clock clock;

	public FileChangeRecognition(CatalogFileLocationRepository catalogFileLocationRepository,
			CatalogLocationWriter catalogLocationWriter, CatalogConvergenceMutations catalogMutations,
			ContentChangeDetector contentChangeDetector, ContentVerificationLauncher contentVerificationLauncher,
			Clock clock) {
		this.catalogFileLocationRepository = catalogFileLocationRepository;
		this.catalogLocationWriter = catalogLocationWriter;
		this.catalogMutations = catalogMutations;
		this.contentChangeDetector = contentChangeDetector;
		this.contentVerificationLauncher = contentVerificationLauncher;
		this.clock = clock;
	}

	/**
	 * @return whether the catalog was brought up to date, so this change needs
	 * nothing further. False is not a failure - it means the ordinary debounced
	 * pass has to answer, exactly as it always did.
	 */
	public boolean recognise(FileSystemChange change) {
		if (change.directory()) {
			return recogniseDirectory(change);
		}

		if (change.kind() == FileChangeKind.MODIFIED) {
			return recogniseModification(change);
		}

		if (change.kind() == FileChangeKind.DELETED) {
			return false;
		}

		// A rename whose destination is a file the catalog already has is not a file
		// arriving somewhere - it is that file being replaced. Applications save by
		// writing a temporary file and swapping it in, so this is the ordinary shape of
		// an edit, not an exception to one.
		if (change.kind() == FileChangeKind.RENAMED && replacesAnExistingFile(change)) {
			return true;
		}

		RecognizedLocation found = locate(change);

		if (found.match() == LocationMatch.AMBIGUOUS) {
			log.info("A file-system identity under {} names more than one catalogued place, so nothing was "
					+ "concluded from it; the reconciliation decides", change.path());

			return false;
		}

		if (found.match() == LocationMatch.NONE) {
			return false;
		}

		// Already there. The journal replay and the live watch deliberately overlap
		// at startup, so one change can be reported twice - and the second report
		// finds the world in the state it describes. Nothing to write, and nothing
		// to walk the library for either.
		if (found.currentPath().equals(change.path())) {
			return true;
		}

		return relocate(change, found);
	}

	/**
	 * A folder that moved, and with it the address of everything catalogued
	 * underneath.
	 *
	 * <p>
	 * The operating system reports one notification for a directory rename and
	 * none for its descendants - a subtree of ten thousand files produces exactly
	 * one record - so this is the one case where the evidence is about something
	 * other than the file the fact is about. It is applied through the same bulk
	 * door an internal folder rename uses, which rewrites every affected row in
	 * one statement and records one fact per file; what differs is only what the
	 * facts say about themselves.
	 *
	 * <p>
	 * The folder's own identity is deliberately not used to find anything. The
	 * catalog has no row for a directory, so there is nothing an identity could
	 * resolve against - the pair of names the operating system gave is the whole
	 * of the recognition, and the catalog's own contents are what is acted on.
	 *
	 * <p>
	 * A refusal is not repaired here. When the destination is already occupied by
	 * something the catalog believes is present, the world on disk is no longer
	 * the one this observation described, and the file system - not this - is the
	 * authority on what already happened. Nothing is undone, because the
	 * application did not cause it; the pass that compares the whole tree is what
	 * settles it.
	 */
	private boolean recogniseDirectory(FileSystemChange change) {
		if (change.kind() != FileChangeKind.RENAMED || change.previousPath() == null) {
			return false;
		}

		Path oldRoot = change.previousPath();
		Path newRoot = change.path();

		// A folder cannot be moved inside itself, so a report saying so describes
		// something other than what it appears to. Acting on it would have the door
		// rewrite each path by a prefix that overlaps its own replacement.
		if (oldRoot.startsWith(newRoot) || newRoot.startsWith(oldRoot)) {
			return false;
		}

		try {
			int moved = catalogMutations.repointFolder(PathUtils.normalize(oldRoot), PathUtils.normalize(newRoot),
					occurredAt(change), CatalogEventSources.WATCHER, CatalogEventEvidence.ANCESTOR_RELOCATED);

			log.info("Recognised a folder moving from {} to {}; {} catalogued file(s) followed it", oldRoot, newRoot,
					moved);

			return true;
		} catch (RuntimeException refusal) {
			// Broad on purpose: whatever stopped the write - the door refusing, the
			// connection blinking - the answer is the same and is never to guess. The
			// change goes back to asking for the ordinary pass, which is slower and
			// sees everything.
			log.info("Could not apply an observed folder relocation from {} to {}: {}", oldRoot, newRoot,
					refusal.getMessage());

			return false;
		}
	}

	/**
	 * A rename that lands on a file the catalog already holds.
	 *
	 * <p>
	 * The catalogued file keeps its identity: what the user did was edit that
	 * picture, whatever the application did to the bytes on the way. The physical
	 * object at the path is a different one now, and whether its content differs is
	 * the digest's to say - so this asks for a verification rather than deciding,
	 * which is the same route a plain write takes.
	 *
	 * <p>
	 * The temporary file is deliberately not consulted. It may have been catalogued
	 * in its own right if a scan happened to pass while it existed, and if so its
	 * row now points at a path that is gone - which is a missing file, and the pass
	 * that compares the tree is what settles those. Nothing here picks a winner
	 * between the two: the file at the path is the one the catalog was already
	 * about, and the other is not made to disappear by fiat.
	 */
	private boolean replacesAnExistingFile(FileSystemChange change) {
		KnownContentRow replaced = catalogFileLocationRepository
				.findKnownContentByPath(PathUtils.normalize(change.path()), PathFlavor.of(change.path()).name())
				.orElse(null);

		if (replaced == null) {
			return false;
		}

		log.info("The file at {} was replaced by another physical object; its content will be verified",
				change.path());

		contentVerificationLauncher.verify(replaced.getCatalogFileId(), PathUtils.normalize(change.path()),
				occurredAt(change), ExecutionTrigger.FILE_EVENT);

		return true;
	}

	/**
	 * A file the catalog knows was written to.
	 *
	 * <p>
	 * Everything here is cheap on purpose. The digest is what settles whether the
	 * bytes are different, and reading one on the thread that polls every half
	 * second would put a gigabyte between the operating system and the next
	 * observation - so this asks only what a stat and one indexed read can answer,
	 * and hands the reading to a durable execution when they cannot answer it.
	 *
	 * <p>
	 * The cheap answer is often enough. A write that leaves the size and the
	 * timestamp exactly as they were is, as far as anything short of reading the
	 * file can tell, nothing having happened - and a notification arrives for
	 * changes of attributes too. Those stop here instead of walking the library.
	 */
	private boolean recogniseModification(FileSystemChange change) {
		KnownContentRow known = catalogFileLocationRepository
				.findKnownContentByPath(PathUtils.normalize(change.path()), PathFlavor.of(change.path()).name())
				.orElse(null);

		if (known == null) {
			// Not catalogued, or not present. Cataloguing it is the walk's job.
			return false;
		}

		BasicFileAttributes attributes = statOf(change.path());

		if (attributes == null) {
			return false;
		}

		ContentState observed = new ContentState(null, attributes.size(),
				CatalogTimestamp.observed(attributes.lastModifiedTime()), change.identity());

		ContentAssessment assessment = contentChangeDetector.assess(knownStateOf(known), observed);

		if (assessment.verdict() == ContentVerdict.UNCHANGED && !assessment.physicallyReplaced()) {
			return true;
		}

		contentVerificationLauncher.verify(known.getCatalogFileId(), PathUtils.normalize(change.path()),
				occurredAt(change), ExecutionTrigger.FILE_EVENT);

		return true;
	}

	private static ContentState knownStateOf(KnownContentRow known) {
		FilesystemIdentity identity = known.getFilesystemIdentityValue() == null ? null
				: new FilesystemIdentity(FilesystemIdentityKind.valueOf(known.getFilesystemIdentityKind()),
						known.getFilesystemIdentityScope(), known.getFilesystemIdentityValue());

		return new ContentState(known.getSha256(), known.getSizeBytes(), known.getModifiedAt(), identity);
	}

	private static BasicFileAttributes statOf(Path path) {
		try {
			return Files.readAttributes(path, BasicFileAttributes.class);
		} catch (IOException _) {
			return null;
		}
	}

	/**
	 * The identity first and the path second, because they are not equally strong.
	 * An identity survives a rename and changes when a file is deleted and another
	 * takes its place, so it is a statement about the object; a path is a statement
	 * about a name, and names get reused.
	 *
	 * <p>
	 * The path branch is also the only way a catalogued file comes to carry an
	 * identity at all: nothing opens a library's worth of files to ask them theirs,
	 * so the first change to reach one is what records it.
	 */
	private RecognizedLocation locate(FileSystemChange change) {
		if (change.identity() != null) {
			List<CatalogFileLocation> carrying = catalogFileLocationRepository.findByFilesystemIdentity(
					change.identity().kind(), change.identity().scope(), change.identity().value());

			if (carrying.size() > 1) {
				return new RecognizedLocation(LocationMatch.AMBIGUOUS, null, null, true);
			}

			if (carrying.size() == 1) {
				CatalogFileLocation location = carrying.get(0);

				return new RecognizedLocation(LocationMatch.UNIQUE, location.getCatalogFile().getId(),
						PathUtils.normalizePath(location.getCurrentPath()), true);
			}
		}

		Path previous = change.previousPath();

		if (previous == null) {
			return new RecognizedLocation(LocationMatch.NONE, null, null, false);
		}

		// Only a file actually occupying that path answers. One that merely used to
		// be there is not what the operating system just renamed, and the door would
		// refuse the write anyway.
		return catalogFileLocationRepository
				.findPresentByPath(PathUtils.normalize(previous), PathFlavor.of(previous).name())
				.map(file -> new RecognizedLocation(LocationMatch.UNIQUE, file.getId(), previous, false))
				.orElseGet(() -> new RecognizedLocation(LocationMatch.NONE, null, null, false));
	}

	/**
	 * When the fact happened - the source's own answer when it has one, and the
	 * clock when it does not.
	 *
	 * <p>
	 * The single place this is decided, so that what a watcher fact's
	 * {@code occurred_at} means is one statement rather than a ternary repeated
	 * at each call. The journal answers, because it replays a window the
	 * application was absent for and knows when each change was recorded;
	 * {@code ReadDirectoryChangesExW} and {@code WatchService} do not, and for
	 * them the honest answer is the moment this accepted the change, which is
	 * within one poll of it. Either way it is the fact's own time, and never
	 * {@code recorded_at}, which the database stamps when the row is written.
	 */
	private Instant occurredAt(FileSystemChange change) {
		return change.occurredAt() == null ? Instant.now(clock) : change.occurredAt();
	}

	/**
	 * Whether the change is a rename or a move is not decided here. The door
	 * classifies it from the two paths under the flavor's own rules, which is where
	 * that rule already lives - restating it in Java is how two answers to one
	 * question start disagreeing.
	 */
	private boolean relocate(FileSystemChange change, RecognizedLocation found) {
		String evidence = found.byIdentity() ? CatalogEventEvidence.FILESYSTEM_IDENTITY_MATCH
				: CatalogEventEvidence.OS_RENAME_PAIR;

		CatalogFactProvenance provenance = new CatalogFactProvenance(occurredAt(change),
				CatalogEventSources.WATCHER, evidence, change.identity());

		try {
			catalogLocationWriter.relocate(new LocationChange(found.catalogFileId(), UuidV7.generate(),
					found.currentPath(), change.path(), provenance));

			log.debug("Recognised {} as catalog file {} moving to {} ({})", change.previousPath(),
					found.catalogFileId(), change.path(), evidence);

			return true;
		} catch (LocationChangeException refusal) {
			// The catalog moved under this observation - another change, an operation of
			// ours, or a destination that filled up in between. The door refusing is the
			// invariant working, and the pass that was going to run anyway is what sorts
			// out a world that is no longer the one this change described.
			log.info("Could not apply an observed relocation of catalog file {} to {}: {}", found.catalogFileId(),
					change.path(), refusal.getMessage());

			return false;
		}
	}
}