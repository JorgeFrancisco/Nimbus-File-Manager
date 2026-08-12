package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.DuplicateExclusionFile;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.DuplicateFolderExclusion;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.DuplicateExclusionFileRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.DuplicateFolderExclusionRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateExclusionFileView;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.EligibilityChanged;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

@ExtendWith(MockitoExtension.class)
class DuplicateExclusionServiceTest {

	@Mock
	private DuplicateExclusionFileRepository fileRepository;

	@Mock
	private CatalogFileRepository catalogFileRepository;

	@Mock
	private DuplicateFolderExclusionRepository folderRepository;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	private DuplicateExclusionService service() {
		return new DuplicateExclusionService(fileRepository, catalogFileRepository, folderRepository,
				eventPublisher);
	}

	@Test
	void excludeFileRecordsTheGenerationTheUserJudged() {
		UUID publicId = UUID.randomUUID();

		CatalogFile file = CatalogFile.builder().id(7L).catalogFilePublicId(publicId).contentRevision(4L).build();

		when(catalogFileRepository.findByCatalogFilePublicId(publicId)).thenReturn(Optional.of(file));
		when(fileRepository.findByCatalogFileId(7L)).thenReturn(Optional.empty());

		boolean created = service().excludeFile(publicId);

		Assertions.assertThat(created).isTrue();

		ArgumentCaptor<DuplicateExclusionFile> saved = ArgumentCaptor.forClass(DuplicateExclusionFile.class);

		verify(fileRepository).save(saved.capture());

		Assertions.assertThat(saved.getValue().getCatalogFile()).isSameAs(file);

		// The decision was made about these bytes, and says nothing about the ones a
		// later replacement puts there.
		Assertions.assertThat(saved.getValue().getContentRevision()).isEqualTo(4L);

		verify(eventPublisher).publishEvent(any(EligibilityChanged.class));
	}

	@Test
	void excludeFileSkipsAnAlreadyExcludedIdWithoutSaving() {
		UUID publicId = UUID.randomUUID();

		CatalogFile file = CatalogFile.builder().id(7L).catalogFilePublicId(publicId).contentRevision(1L).build();

		when(catalogFileRepository.findByCatalogFilePublicId(publicId)).thenReturn(Optional.of(file));
		when(fileRepository.findByCatalogFileId(7L))
				.thenReturn(Optional.of(DuplicateExclusionFile.builder().id(3L).contentRevision(1L).build()));

		Assertions.assertThat(service().excludeFile(publicId)).isFalse();
		verify(fileRepository, never()).save(any());
		verify(fileRepository, never()).delete(any());
		verify(eventPublisher, never()).publishEvent(any(EligibilityChanged.class));
	}

	/**
	 * The user is looking at a picture they have never judged - the one the
	 * standing row was about is gone - so the click is a new judgement and takes
	 * the old one's place. Announced, because a file that was being offered stops
	 * being offered.
	 */
	@Test
	void excludeFileReplacesAJudgementMadeAboutContentThatHasSinceChanged() {
		UUID publicId = UUID.randomUUID();

		CatalogFile file = CatalogFile.builder().id(7L).catalogFilePublicId(publicId).contentRevision(4L).build();

		DuplicateExclusionFile stale = DuplicateExclusionFile.builder().id(3L).contentRevision(3L).build();

		when(catalogFileRepository.findByCatalogFilePublicId(publicId)).thenReturn(Optional.of(file));
		when(fileRepository.findByCatalogFileId(7L)).thenReturn(Optional.of(stale));

		Assertions.assertThat(service().excludeFile(publicId)).isTrue();

		ArgumentCaptor<DuplicateExclusionFile> saved = ArgumentCaptor.forClass(DuplicateExclusionFile.class);

		// The delete has to reach the database before the insert, or the one
		// judgement per file the table enforces refuses the new one.
		InOrder order = inOrder(fileRepository);

		order.verify(fileRepository).delete(stale);
		order.verify(fileRepository).flush();
		order.verify(fileRepository).save(saved.capture());

		Assertions.assertThat(saved.getValue().getContentRevision()).isEqualTo(4L);

		verify(eventPublisher).publishEvent(any(EligibilityChanged.class));
	}

	@Test
	void excludeFileIgnoresNull() {
		Assertions.assertThat(service().excludeFile(null)).isFalse();
		verify(fileRepository, never()).save(any());
	}

	/**
	 * A file the catalog no longer has cannot be judged, and this is reached from
	 * the screen rather than being defensive: the list the click came from was
	 * rendered before whatever removed the row.
	 */
	@Test
	void excludeFileIgnoresAnIdTheCatalogNoLongerHas() {
		UUID publicId = UUID.randomUUID();

		when(catalogFileRepository.findByCatalogFilePublicId(publicId)).thenReturn(Optional.empty());

		Assertions.assertThat(service().excludeFile(publicId)).isFalse();

		verify(fileRepository, never()).save(any());
		verify(eventPublisher, never()).publishEvent(any(EligibilityChanged.class));
	}

	@Test
	void excludeFolderStoresAForwardSlashPathAndReportsCreated() {
		Assumptions.assumeTrue(File.separatorChar == '\\');
		when(folderRepository.existsByFolderPath("C:/Fotos/viagem")).thenReturn(false);

		boolean created = service().excludeFolder("C:\\Fotos\\viagem\\");

		Assertions.assertThat(created).isTrue();

		ArgumentCaptor<DuplicateFolderExclusion> saved = ArgumentCaptor.forClass(DuplicateFolderExclusion.class);
		verify(folderRepository).save(saved.capture());
		Assertions.assertThat(saved.getValue().getFolderPath()).isEqualTo("C:/Fotos/viagem");

		// A folder taken out of comparison takes every file at or under it with it,
		// which is the same change of eligibility a single file makes.
		verify(eventPublisher).publishEvent(any(EligibilityChanged.class));
	}

	@Test
	void excludeFolderSkipsBlankAndAlreadyExcludedWithoutSaving() {
		Assertions.assertThat(service().excludeFolder("  ")).isFalse();
		Assertions.assertThat(service().excludeFolder(null)).isFalse();

		when(folderRepository.existsByFolderPath(any())).thenReturn(true);
		Assertions.assertThat(service().excludeFolder("C:/Fotos")).isFalse();

		verify(folderRepository, never()).save(any());
		verify(eventPublisher, never()).publishEvent(any(EligibilityChanged.class));
	}

	/** The same pair for lifting one: a folder coming back is a change too. */
	@Test
	void liftingAFolderExclusionAnnouncesThatEligibilityChanged() {
		when(folderRepository.existsById(9L)).thenReturn(true);

		service().removeFolderExclusion(9L);

		verify(folderRepository).deleteById(9L);
		verify(eventPublisher).publishEvent(any(EligibilityChanged.class));
	}

	@Test
	void removingAFolderExclusionThatIsNotThereAnnouncesNothing() {
		when(folderRepository.existsById(9L)).thenReturn(false);

		service().removeFolderExclusion(9L);

		verify(folderRepository, never()).deleteById(any());
		verify(eventPublisher, never()).publishEvent(any(EligibilityChanged.class));
	}

	@Test
	void readAndRemoveDelegateToTheRepositories() {
		UUID id = UUID.randomUUID();
		when(fileRepository.findApplyingCatalogFilePublicIds()).thenReturn(List.of(id));
		when(folderRepository.findAllFolderPaths()).thenReturn(List.of("C:/Fotos"));
		when(fileRepository.existsById(7L)).thenReturn(true);
		when(folderRepository.existsById(9L)).thenReturn(true);

		DuplicateExclusionService service = service();

		Assertions.assertThat(service.excludedFilePublicIds()).containsExactly(id);
		Assertions.assertThat(service.excludedFolders()).containsExactly("C:/Fotos");

		service.removeFileExclusion(7L);
		service.removeFolderExclusion(9L);

		verify(fileRepository).deleteById(7L);
		verify(folderRepository).deleteById(9L);
	}

	/**
	 * Lifting an exclusion returns files to the analysed set, which is the whole
	 * reason the relations about them were kept rather than deleted.
	 */
	@Test
	void liftingAnExclusionAnnouncesThatEligibilityChanged() {
		when(fileRepository.existsById(7L)).thenReturn(true);

		service().removeFileExclusion(7L);

		verify(fileRepository).deleteById(7L);
		verify(eventPublisher).publishEvent(any(EligibilityChanged.class));
	}

	/**
	 * Removing an id that is not there changed nothing, and an announcement of a
	 * change that did not happen would put a regroup on the queue for nothing.
	 */
	@Test
	void removingAnExclusionThatIsNotThereAnnouncesNothing() {
		when(fileRepository.existsById(7L)).thenReturn(false);

		service().removeFileExclusion(7L);

		verify(fileRepository, never()).deleteById(any());
		verify(eventPublisher, never()).publishEvent(any(EligibilityChanged.class));
	}

	@Test
	void managementListsDelegateToTheRepositories() {
		// The judgement still bears on the file that is there: the content it was made
		// about has not been replaced.
		DuplicateExclusionFileView view = new DuplicateExclusionFileView(1L, UUID.randomUUID(), "C:/x.jpg",
				Instant.EPOCH, true);
		DuplicateFolderExclusion folder = DuplicateFolderExclusion.builder().folderPath("C:/f").build();
		when(fileRepository.findAllViews()).thenReturn(List.of(view));
		when(folderRepository.findAll()).thenReturn(List.of(folder));

		DuplicateExclusionService service = service();

		Assertions.assertThat(service.fileExclusions()).containsExactly(view);
		Assertions.assertThat(service.folderExclusions()).containsExactly(folder);
	}

	@Test
	void isUnderExcludedFolderMatchesTheFolderItsSubfoldersAndNormalizesSeparators() {
		DuplicateExclusionService service = service();

		List<String> excluded = List.of("C:/Fotos");

		Assertions.assertThat(service.isUnderExcludedFolder("C:/Fotos", excluded)).isTrue();
		Assertions.assertThat(service.isUnderExcludedFolder("C:/Fotos/2024", excluded)).isTrue();
		Assertions.assertThat(service.isUnderExcludedFolder("C:\\Fotos\\2024", excluded)).isTrue();
		Assertions.assertThat(service.isUnderExcludedFolder("C:/FotosOutras", excluded)).isFalse();
		Assertions.assertThat(service.isUnderExcludedFolder("D:/Outra", excluded)).isFalse();
		Assertions.assertThat(service.isUnderExcludedFolder(null, excluded)).isFalse();
	}
}