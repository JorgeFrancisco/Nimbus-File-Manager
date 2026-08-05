package br.com.jorgemelo.nimbusfilemanager.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryBatchItemResult;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryPersistenceResult;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryScanRequest;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.ScanOptions;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.InventoryPersistenceAction;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.ProcessResult;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataOptions;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.facade.MetadataFacade;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * One batch, and the tally it leaves behind.
 *
 * <p>
 * The counters used to be fields of a step-scoped bean, which meant Spring
 * Batch decided who could see them. They now travel as an argument, so what
 * this asserts is that each outcome lands in the right column and that the
 * progress written afterwards reflects the whole run rather than this batch.
 */
class InventoryBatchWriterTest {

	private final InventoryPersistenceService inventoryPersistenceService = mock(InventoryPersistenceService.class);
	private final MetadataFacade metadataFacade = mock(MetadataFacade.class);
	private final ExecutionErrorService executionErrorService = mock(ExecutionErrorService.class);
	private final ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);
	private final ExecutionCancellationService executionCancellationService = mock(
			ExecutionCancellationService.class);

	private final InventoryBatchWriter writer = new InventoryBatchWriter(inventoryPersistenceService, metadataFacade,
			executionErrorService, executionProgressService, executionCancellationService);

	@Test
	void countsAFailedFileAsAnErrorAndRecordsIt(@TempDir Path folder) {
		Path file = folder.resolve("broken.jpg");

		IOException failure = new IOException("unreadable");

		when(inventoryPersistenceService.saveOrCacheBatch(any(), any(), any(), any(), any(), any()))
				.thenReturn(List.of(new InventoryBatchItemResult(file, null, failure)));

		InventoryCounters counters = new InventoryCounters();

		Execution execution = execution();

		writer.write(execution, request(folder), List.of(file), counters);

		assertThat(counters.errors()).isEqualTo(1);
		assertThat(counters.found()).isEqualTo(1);

		verify(executionErrorService).save(file, failure, execution);
		verify(executionProgressService).updateProgress(execution, 1, 0, 0, 1, file);
	}

	@Test
	void reportsProgressForTheWholeRunNotJustThisBatch(@TempDir Path folder) {
		Path file = folder.resolve("a.jpg");

		when(inventoryPersistenceService.saveOrCacheBatch(any(), any(), any(), any(), any(), any()))
				.thenReturn(List.of());

		InventoryCounters counters = new InventoryCounters();

		Execution execution = execution();

		writer.write(execution, request(folder), List.of(file), counters);
		writer.write(execution, request(folder), List.of(file), counters);

		assertThat(counters.found()).isEqualTo(2);

		verify(executionProgressService).updateProgress(eq(execution), eq(2), eq(0), eq(0), eq(0),
				nullable(Path.class));
	}

	/**
	 * The three outcomes a catalogued file can have, each landing in its own
	 * column - the switch that used to live in a step-scoped writer.
	 */
	@Test
	void countsAnalysedAndCachedFilesSeparately(@TempDir Path folder) {
		Path analysed = folder.resolve("new.jpg");
		Path cached = folder.resolve("known.jpg");

		when(inventoryPersistenceService.saveOrCacheBatch(any(), any(), any(), any(), any(), any()))
				.thenReturn(List.of(result(analysed, ProcessResult.ANALYZED), result(cached, ProcessResult.CACHE)));

		InventoryCounters counters = new InventoryCounters();

		writer.write(execution(), request(folder), List.of(analysed, cached), counters);

		assertThat(counters.analyzed()).isEqualTo(1);
		assertThat(counters.cacheHits()).isEqualTo(1);
		assertThat(counters.errors()).isZero();
	}

	@Test
	void countsAFileTheExtractionCouldNotReadAsAnError(@TempDir Path folder) {
		Path file = folder.resolve("odd.jpg");

		when(inventoryPersistenceService.saveOrCacheBatch(any(), any(), any(), any(), any(), any()))
				.thenReturn(List.of(result(file, ProcessResult.ERROR)));

		InventoryCounters counters = new InventoryCounters();

		writer.write(execution(), request(folder), List.of(file), counters);

		assertThat(counters.errors()).isEqualTo(1);
	}

	private InventoryBatchItemResult result(Path file, ProcessResult outcome) {
		return InventoryBatchItemResult.of(file, new InventoryPersistenceResult(outcome,
				InventoryPersistenceAction.CREATED));
	}

	private Execution execution() {
		Execution execution = Execution.builder().executionType(ExecutionType.INVENTORY)
				.status(ExecutionStatus.RUNNING).build();

		execution.setId(1L);

		return execution;
	}

	private InventoryScanRequest request(Path folder) {
		return new InventoryScanRequest(folder, new ScanOptions(true, false, List.of(), List.of(), List.of()),
				new MetadataOptions(true, false));
	}
}