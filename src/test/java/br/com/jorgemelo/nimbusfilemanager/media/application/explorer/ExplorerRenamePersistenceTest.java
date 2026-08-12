package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.ContentReconciliation;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MoveBaseline;
import br.com.jorgemelo.nimbusfilemanager.shared.AppliedLocationChanges;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.LocationChange;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.CatalogLocationWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

/**
 * What this class is left with once the placement is not its to write.
 *
 * <p>
 * Where it used to move both rows itself, it now states the change and lets the
 * write door apply it - so what is worth checking here is that it states the
 * right one, and that the extension, which a rename can change and which is not
 * part of where the file is, still follows the new name.
 */
class ExplorerRenamePersistenceTest {

	private static final Instant NOW = Instant.parse("2026-08-06T10:00:00Z");
	/**
	 * The identity the movement reserved before the file moved. It is not the
	 * execution's: a folder rename has one execution and a fact per file, so a
	 * command's identity could never stand in for a fact's.
	 */
	private static final UUID EVENT = UuidV7.generate();

	private final CatalogFileRepository catalogFileRepository = mock(CatalogFileRepository.class);
	private final CatalogFileLocationRepository catalogFileLocationRepository = mock(
			CatalogFileLocationRepository.class);
	private final CatalogLocationWriter catalogLocationWriter = mock(CatalogLocationWriter.class);

	/** A catalogued file, at the name it is about to be renamed from. */
	private CatalogFile named(long id) {
		CatalogFile file = CatalogFile.builder().id(id).extension("jpg").build();

		file.setLocation(CatalogFileLocation.builder().catalogFile(file)
				.currentPath("D:\\library\\before.jpg").pathFlavor(PathFlavor.WINDOWS).build());

		return file;
	}

	/** The name as the row now holds it, which the entry has to agree with. */
	@BeforeEach
	void thePlacementDoorAnswers() {
		lenient().when(catalogLocationWriter.rename(any()))
				.thenAnswer(invocation -> AppliedLocationChanges.applying(invocation.getArgument(0)));
	}

	private final ContentReconciliation contentReconciliation = mock(ContentReconciliation.class);

	private final ExplorerRenamePersistence persistence = new ExplorerRenamePersistence(catalogFileRepository,
			catalogFileLocationRepository, catalogLocationWriter, contentReconciliation,
			Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void statesTheRenameToTheWriteDoorAndFollowsTheNewExtension(@TempDir Path folder) {
		Path source = folder.resolve("photo.jpg");
		Path target = folder.resolve("holiday.jpeg");

		CatalogFile stored = named(7L);

		when(catalogFileLocationRepository.findPresentByPath(any(), any())).thenReturn(Optional.of(stored));

		assertThat(persistence.rename(source, target, EVENT, moved())).isTrue();

		ArgumentCaptor<LocationChange> change = ArgumentCaptor.forClass(LocationChange.class);

		verify(catalogLocationWriter).rename(change.capture());

		assertThat(change.getValue().catalogFileId()).isEqualTo(7L);
		assertThat(change.getValue().expectedCurrentPath()).isEqualTo(source);
		assertThat(change.getValue().newPath()).isEqualTo(target);
		assertThat(change.getValue().provenance().occurredAt()).isEqualTo(NOW);
		assertThat(change.getValue().provenance().source()).isEqualTo(CatalogEventSources.EXPLORER);

		assertThat(stored.getExtension()).isEqualTo("jpeg");

		verify(catalogFileRepository).save(stored);
	}

	/**
	 * The fact goes out under the identity the movement reserved before anything
	 * moved, which is what lets an execution that died mid-write recognise its own
	 * work on the retry instead of recording the rename a second time.
	 */
	@Test
	void carriesTheReservedFactIdentitySoARetryIsRecognised(@TempDir Path folder) {
		when(catalogFileLocationRepository.findPresentByPath(any(), any()))
				.thenReturn(Optional.of(named(7L)));

		persistence.rename(folder.resolve("photo.jpg"), folder.resolve("holiday.jpg"), EVENT, moved());

		ArgumentCaptor<LocationChange> change = ArgumentCaptor.forClass(LocationChange.class);

		verify(catalogLocationWriter).rename(change.capture());

		assertThat(change.getValue().eventId()).isEqualTo(EVENT);
	}

	/**
	 * A file the catalog never saw is still renamed on disk; there is simply no row
	 * to repoint, and inventing one would be inventing history.
	 */
	@Test
	void writesNothingForAFileTheCatalogDoesNotKnow(@TempDir Path folder) {
		when(catalogFileLocationRepository.findPresentByPath(any(), any())).thenReturn(Optional.empty());

		assertThat(persistence.rename(folder.resolve("photo.jpg"), folder.resolve("holiday.jpg"), EVENT, moved()))
				.isFalse();

		verify(catalogLocationWriter, never()).rename(any());
		verify(catalogFileRepository, never()).save(any());
	}

	/**
	 * The digest the rename's own secure move already proved, taken up rather than
	 * discarded. A rename does not change bytes, so agreeing is the ordinary
	 * outcome - and disagreeing means the catalog was stale before the rename,
	 * which this is the first thing in a position to notice.
	 */
	@Test
	void handsTheDigestTheMoveProvedToTheReconciliation(@TempDir Path folder) {
		CatalogFile stored = named(7L);

		when(catalogFileLocationRepository.findPresentByPath(any(), any())).thenReturn(Optional.of(stored));

		persistence.rename(folder.resolve("photo.jpg"), folder.resolve("holiday.jpg"), EVENT, moved());

		verify(contentReconciliation).reconcileFromDigest(stored, "digest-proved-by-the-move", 1024L,
				CatalogEventSources.EXPLORER, NOW);
	}

	/** The catalog is asked about the normalised path, not the raw one. */
	@Test
	void asksTheCatalogAboutTheNormalisedPath(@TempDir Path folder) {
		Path source = folder.resolve("photo.jpg");

		when(catalogFileLocationRepository.findPresentByPath(any(), any())).thenReturn(Optional.empty());

		persistence.rename(source, folder.resolve("holiday.jpg"), EVENT, moved());

		verify(catalogFileLocationRepository).findPresentByPath(PathUtils.normalize(source),
				PathFlavor.of(source).name());
	}

	/** What the verified move proved about the file it placed. */
	private static MoveBaseline moved() {
		return new MoveBaseline(1024L, "digest-proved-by-the-move");
	}
}