package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

@ExtendWith(MockitoExtension.class)
class FileExplorerReconcileServiceTest {

	@Mock
	private CatalogFileRepository catalogFileRepository;

	private FileExplorerReconcileService service() {
		return new FileExplorerReconcileService(catalogFileRepository, Clock.systemDefaultZone());
	}

	@Test
	void marksTheGivenRecordsMissingWithATimestamp() {
		when(catalogFileRepository.markMissingByIds(eq(List.of(1L, 2L)), any(LocalDateTime.class))).thenReturn(2);

		service().markMissing(List.of(1L, 2L));

		verify(catalogFileRepository).markMissingByIds(eq(List.of(1L, 2L)), any(LocalDateTime.class));
	}

	@Test
	void doesNothingForAnEmptyList() {
		service().markMissing(List.of());

		verifyNoInteractions(catalogFileRepository);
	}
}