package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.PreparedMovements;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.MovementRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PreparedMovement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.MovementWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * What an Explorer relocation writes down before it does anything.
 *
 * <p>
 * The operations are the record a retry reads, so how a run <em>ends</em>
 * matters as much as how it starts: one that did not happen has to say so.
 * Movements left pending would tell a later reader that the disk moved and the
 * catalog is behind, and the repair for that is not the repair for a rename
 * that simply failed.
 */
class ExplorerRelocationPlanTest {

	private static final long EXECUTION_ID = 7L;

	private final CatalogFileLocationRepository catalogFileLocationRepository = mock(
			CatalogFileLocationRepository.class);
	private final MovementWriter movementWriter = mock(MovementWriter.class);

	private final ExplorerRelocationPlan plan = new ExplorerRelocationPlan(catalogFileLocationRepository,
			movementWriter);

	@Test
	void aRenameThatDidNotHappenSaysSoOnEveryOperationItReserved(@TempDir Path folder) {
		PreparedMovement first = PreparedMovements.pending(1L, 10L, folder.resolve("a.jpg"),
				folder.resolve("b.jpg"));
		PreparedMovement second = PreparedMovements.pending(2L, 11L, folder.resolve("c.jpg"),
				folder.resolve("d.jpg"));

		plan.abandon(execution(), List.of(first, second), MovementReason.IO_ERROR);

		ArgumentCaptor<List<UUID>> ids = ArgumentCaptor.captor();

		verify(movementWriter).markFailed(anyLong(), ids.capture(), any());

		Assertions.assertThat(ids.getValue()).containsExactly(first.movementPublicId(), second.movementPublicId());
	}

	@Test
	void aRunThatReservedNothingHasNothingToAbandonOrSettle() {
		plan.abandon(execution(), List.of(), MovementReason.IO_ERROR);
		plan.settle(execution(), List.of());

		verify(movementWriter, never()).markFailed(anyLong(), any(), any());
		verify(movementWriter, never()).markMoved(anyLong(), any());
	}

	/**
	 * A file the catalog has never had an opinion on. The user is free to rename
	 * it and the disk will obey; inventing an operation would put a row in the
	 * history for something the catalog knows nothing about.
	 */
	@Test
	void afileNobodyCataloguedReservesNothing(@TempDir Path folder) {
		when(catalogFileLocationRepository.findPresentByPath(anyString(), anyString())).thenReturn(Optional.empty());
		when(movementWriter.reserved(EXECUTION_ID)).thenReturn(List.of());

		Assertions.assertThat(plan.reserve(execution(), folder.resolve("stranger.jpg"), folder.resolve("renamed.jpg"),
				false)).isEmpty();

		verify(movementWriter, never()).prepare(anyLong(), anyList());
	}

	@Test
	void aCataloguedFileReservesOneOperationForTheMoveItIsAbout(@TempDir Path folder) {
		Path before = folder.resolve("before.jpg");
		Path after = folder.resolve("after.jpg");

		when(catalogFileLocationRepository.findPresentByPath(PathUtils.normalize(before),
				PathFlavor.of(before).name())).thenReturn(Optional.of(CatalogFiles.at(10L, before)));

		plan.reserve(execution(), before, after, false);

		ArgumentCaptor<List<MovementRequest>> requests = ArgumentCaptor.captor();

		verify(movementWriter).prepare(anyLong(), requests.capture());

		Assertions.assertThat(requests.getValue()).singleElement()
				.extracting(MovementRequest::catalogFileId, MovementRequest::requestedSource,
						MovementRequest::requestedTarget)
				.containsExactly(10L, before, after);
	}

	private Execution execution() {
		return Execution.builder().id(EXECUTION_ID).build();
	}
}