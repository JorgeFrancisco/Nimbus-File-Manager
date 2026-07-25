package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.DuplicateFileExclusion;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.DuplicateFolderExclusion;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.DuplicateFileExclusionRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.DuplicateFolderExclusionRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateFileExclusionView;

@ExtendWith(MockitoExtension.class)
class DuplicateExclusionServiceTest {

	@Mock
	private DuplicateFileExclusionRepository fileRepository;

	@Mock
	private DuplicateFolderExclusionRepository folderRepository;

	private DuplicateExclusionService service() {
		return new DuplicateExclusionService(fileRepository, folderRepository);
	}

	@Test
	void excludeFileStoresAnUnseenPublicIdAndReportsCreated() {
		UUID publicId = UUID.randomUUID();
		when(fileRepository.existsByPublicId(publicId)).thenReturn(false);

		boolean created = service().excludeFile(publicId);

		Assertions.assertThat(created).isTrue();

		ArgumentCaptor<DuplicateFileExclusion> saved = ArgumentCaptor.forClass(DuplicateFileExclusion.class);
		verify(fileRepository).save(saved.capture());
		Assertions.assertThat(saved.getValue().getPublicId()).isEqualTo(publicId);
	}

	@Test
	void excludeFileSkipsAnAlreadyExcludedIdWithoutSaving() {
		UUID publicId = UUID.randomUUID();
		when(fileRepository.existsByPublicId(publicId)).thenReturn(true);

		Assertions.assertThat(service().excludeFile(publicId)).isFalse();
		verify(fileRepository, never()).save(any());
	}

	@Test
	void excludeFileIgnoresNull() {
		Assertions.assertThat(service().excludeFile(null)).isFalse();
		verify(fileRepository, never()).save(any());
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
	}

	@Test
	void excludeFolderSkipsBlankAndAlreadyExcludedWithoutSaving() {
		Assertions.assertThat(service().excludeFolder("  ")).isFalse();
		Assertions.assertThat(service().excludeFolder(null)).isFalse();

		when(folderRepository.existsByFolderPath(any())).thenReturn(true);
		Assertions.assertThat(service().excludeFolder("C:/Fotos")).isFalse();

		verify(folderRepository, never()).save(any());
	}

	@Test
	void readAndRemoveDelegateToTheRepositories() {
		UUID id = UUID.randomUUID();
		when(fileRepository.findAllPublicIds()).thenReturn(List.of(id));
		when(folderRepository.findAllFolderPaths()).thenReturn(List.of("C:/Fotos"));

		DuplicateExclusionService service = service();

		Assertions.assertThat(service.excludedFilePublicIds()).containsExactly(id);
		Assertions.assertThat(service.excludedFolders()).containsExactly("C:/Fotos");

		service.removeFileExclusion(7L);
		service.removeFolderExclusion(9L);

		verify(fileRepository).deleteById(7L);
		verify(folderRepository).deleteById(9L);
	}

	@Test
	void managementListsDelegateToTheRepositories() {
		DuplicateFileExclusionView view = new DuplicateFileExclusionView(1L, UUID.randomUUID(), "C:/x.jpg", null);
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