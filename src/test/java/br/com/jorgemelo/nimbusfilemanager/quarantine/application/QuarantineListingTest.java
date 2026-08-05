package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineItemResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * What the Quarentena screen is shown. Reading only: this holds no capability
 * to move anything, which is the whole reason it was split from the restoring.
 */
class QuarantineListingTest {

	private final MovementRepository movementRepository = mock(MovementRepository.class);
	private final QuarantineListing listing = new QuarantineListing(movementRepository);

	@Test
	void listsQuarantinedFilesWithLiveOriginAndConflictFlags(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");

		returns(quarantineMovement(origin.resolve("a.jpg"), quarantine));

		List<QuarantineItemResponse> items = listing.list(PageRequest.of(0, 50)).getContent();

		Assertions.assertThat(items).hasSize(1);

		QuarantineItemResponse item = items.get(0);

		Assertions.assertThat(item.fileName()).isEqualTo("a.jpg");
		Assertions.assertThat(item.presentInQuarantine()).isTrue();
		Assertions.assertThat(item.originFolderExists()).isTrue();
		Assertions.assertThat(item.conflict()).isFalse();
	}

	/**
	 * The lightbox is offered from the extension, so a name without one has to fall
	 * through to "nothing to open" - the id that would serve the content exists
	 * either way, and an offer that opens an empty viewer is worse than no offer.
	 */
	@Test
	void offersNoPreviewForAFileWhoseNameCarriesNoExtension(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path photoQuarantine = writeQuarantineCopy(tmp, "10__a.jpg", "content");
		Path plainQuarantine = writeQuarantineCopy(tmp, "11__README", "content");

		Movement photo = quarantineMovement(origin.resolve("a.jpg"), photoQuarantine);
		Movement plain = quarantineMovement(origin.resolve("README"), plainQuarantine);

		when(photo.getCatalogFile().getPublicId()).thenReturn(UUID.randomUUID());
		when(plain.getCatalogFile().getPublicId()).thenReturn(UUID.randomUUID());

		returns(photo, plain);

		List<QuarantineItemResponse> items = listing.list(PageRequest.of(0, 50)).getContent();

		Assertions.assertThat(items.get(0).image()).isTrue();
		Assertions.assertThat(items.get(0).previewUrl()).isNotNull();

		Assertions.assertThat(items.get(1).image()).isFalse();
		Assertions.assertThat(items.get(1).video()).isFalse();
		Assertions.assertThat(items.get(1).previewUrl()).isNull();
	}

	/**
	 * A name ending in a dot has no extension, however much it looks like one - and
	 * the file is cataloged with a type that decides nothing, so the extension is
	 * what is left to ask. The media id is there, which is what makes the absent
	 * preview an answer about the name rather than about a missing record.
	 */
	@Test
	void offersNoPreviewForANameThatEndsInADot(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));

		returns(typedMovement(origin.resolve("holiday."), writeQuarantineCopy(tmp, "10__holiday.", "content"), null,
				UUID.randomUUID()));

		QuarantineItemResponse item = listing.list(PageRequest.of(0, 50)).getContent().get(0);

		Assertions.assertThat(item.mediaPublicId()).isNotNull();
		Assertions.assertThat(item.fileType()).isEqualTo("OTHER");
		Assertions.assertThat(item.image()).isFalse();
		Assertions.assertThat(item.previewUrl()).isNull();
	}

	@Test
	void flagsAMissingOriginAndFallsBackToTheSizeOnDisk(@TempDir Path tmp) throws Exception {
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "abcd");

		Movement movement = quarantineMovement(tmp.resolve("gone").resolve("a.jpg"), quarantine);

		when(movement.getCatalogFile().getSizeBytes()).thenReturn(null);

		returns(movement);

		QuarantineItemResponse item = listing.list(PageRequest.of(0, 50)).getContent().get(0);

		Assertions.assertThat(item.originFolderExists()).isFalse();
		Assertions.assertThat(item.presentInQuarantine()).isTrue();
		Assertions.assertThat(item.sizeBytes()).isEqualTo(4L);
		Assertions.assertThat(item.sizeLabel()).isEqualTo("4 B");
	}

	@Test
	void buildsMediaUrlsForImages(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__a.jpg", "img");

		UUID publicId = UUID.randomUUID();

		returns(typedMovement(origin.resolve("a.jpg"), quarantine, FileType.PHOTO, publicId));

		QuarantineItemResponse item = listing.list(PageRequest.of(0, 50)).getContent().get(0);

		Assertions.assertThat(item.image()).isTrue();
		Assertions.assertThat(item.mediaPublicId()).isEqualTo(publicId);
		Assertions.assertThat(item.previewUrl()).isEqualTo("/api/media/" + publicId + "/content");
	}

	/**
	 * Without a catalog record the listing still has to render: the type falls back
	 * to OTHER, there is no media id to build a preview URL from, and the size is
	 * read off the quarantine copy.
	 */
	@Test
	void rendersAnItemWhoseCatalogRecordIsGone(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));
		Path quarantine = writeQuarantineCopy(tmp, "10__orphan.jpg", "1234567");

		returns(movement(origin.resolve("orphan.jpg"), quarantine, null));

		QuarantineItemResponse item = listing.list(PageRequest.of(0, 50)).getContent().get(0);

		Assertions.assertThat(item.mediaPublicId()).isNull();
		Assertions.assertThat(item.previewUrl()).isNull();
		Assertions.assertThat(item.fileType()).isEqualTo("OTHER");
		Assertions.assertThat(item.sizeBytes()).isEqualTo(7L);
	}

	/**
	 * A quarantine copy that vanished leaves nothing to measure, so the screen gets
	 * a null size and the em-dash placeholder instead of a bogus zero.
	 */
	@Test
	void reportsNoSizeWhenNeitherTheCatalogNorTheDiskCanProvideIt(@TempDir Path tmp) {
		Path origin = tmp.resolve("library");

		CatalogFile catalogFile = mock(CatalogFile.class);

		when(catalogFile.getFileType()).thenReturn(FileType.PHOTO);
		when(catalogFile.getSizeBytes()).thenReturn(null);

		returns(movement(origin.resolve("missing.jpg"), tmp.resolve("trash").resolve("nowhere.jpg"), catalogFile));

		QuarantineItemResponse item = listing.list(PageRequest.of(0, 50)).getContent().get(0);

		Assertions.assertThat(item.sizeBytes()).isNull();
		Assertions.assertThat(item.sizeLabel()).isEqualTo("—");
		Assertions.assertThat(item.presentInQuarantine()).isFalse();
	}

	/**
	 * The card decides which viewer to open from these flags, so each previewable
	 * kind has to come back set on its own and nothing else.
	 */
	@Test
	void flagsEachPreviewableKindOnItsOwn(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));

		Movement video = typedMovement(origin.resolve("clip.mp4"), writeQuarantineCopy(tmp, "10__clip.mp4", "content"),
				FileType.VIDEO, UUID.randomUUID());
		Movement pdf = typedMovement(origin.resolve("manual.pdf"), writeQuarantineCopy(tmp, "11__manual.pdf", "c"),
				FileType.PDF, UUID.randomUUID());
		Movement text = typedMovement(origin.resolve("notes.txt"), writeQuarantineCopy(tmp, "12__notes.txt", "c"),
				FileType.TEXT, UUID.randomUUID());
		Movement audio = typedMovement(origin.resolve("song.mp3"), writeQuarantineCopy(tmp, "13__song.mp3", "c"),
				FileType.AUDIO, UUID.randomUUID());

		returns(video, pdf, text, audio);

		List<QuarantineItemResponse> items = listing.list(PageRequest.of(0, 50)).getContent();

		Assertions.assertThat(items.get(0).video()).isTrue();
		Assertions.assertThat(items.get(0).image()).isFalse();
		Assertions.assertThat(items.get(1).pdf()).isTrue();
		Assertions.assertThat(items.get(2).text()).isTrue();
		Assertions.assertThat(items.get(3).audio()).isTrue();
		Assertions.assertThat(items).allSatisfy(item -> Assertions.assertThat(item.previewUrl()).isNotNull());
	}

	/**
	 * A kind with no viewer gets no preview URL even though the media is perfectly
	 * cataloged - the card then falls back to the generic icon.
	 */
	@Test
	void offersNoPreviewForAKindWithNoViewer(@TempDir Path tmp) throws Exception {
		Path origin = Files.createDirectories(tmp.resolve("library"));

		returns(typedMovement(origin.resolve("backup.zip"), writeQuarantineCopy(tmp, "10__backup.zip", "content"),
				FileType.ZIP, UUID.randomUUID()));

		QuarantineItemResponse item = listing.list(PageRequest.of(0, 50)).getContent().get(0);

		Assertions.assertThat(item.previewUrl()).isNull();
		Assertions.assertThat(item.image()).isFalse();
		Assertions.assertThat(item.video()).isFalse();
	}

	private void returns(Movement... movements) {
		when(movementRepository.findByStatusAndReasonInOrderByIdDesc(eq(MovementStatus.MOVED),
				eq(QuarantineConstants.QUARANTINED_REASONS), any())).thenReturn(new PageImpl<>(List.of(movements)));
	}

	private Path writeQuarantineCopy(Path tmp, String name, String content) throws Exception {
		Path folder = Files.createDirectories(tmp.resolve("trash").resolve("exec-1"));

		return Files.writeString(folder.resolve(name), content);
	}

	private Movement quarantineMovement(Path original, Path quarantine) {
		CatalogFile catalogFile = mock(CatalogFile.class);

		when(catalogFile.getFileType()).thenReturn(null);
		when(catalogFile.getSizeBytes()).thenReturn(123L);

		return movement(original, quarantine, catalogFile);
	}

	private Movement typedMovement(Path original, Path quarantine, FileType fileType, UUID mediaPublicId) {
		CatalogFile catalogFile = mock(CatalogFile.class);

		when(catalogFile.getFileType()).thenReturn(fileType);
		when(catalogFile.getSizeBytes()).thenReturn(7L);
		when(catalogFile.getPublicId()).thenReturn(mediaPublicId);

		return movement(original, quarantine, catalogFile);
	}

	private Movement movement(Path original, Path quarantine, CatalogFile catalogFile) {
		Execution execution = mock(Execution.class);

		when(execution.getPublicId()).thenReturn(UUID.randomUUID());

		return Movement.builder().publicId(UUID.randomUUID()).execution(execution).catalogFile(catalogFile)
				.sourcePath(PathUtils.normalize(original)).targetPath(PathUtils.normalize(quarantine))
				.status(MovementStatus.MOVED).reason(MovementReason.DUPLICATE_QUARANTINED).movedAt(LocalDateTime.now())
				.build();
	}
}