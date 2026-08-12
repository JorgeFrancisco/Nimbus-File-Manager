package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;

/**
 * Pins the catalog contract: every factory maps to its stable code and carries
 * exactly the typed arguments the read side expects to fill the placeholders.
 */
class ExecutionMessagesTest {

	@Test
	void noArgumentFactoriesCarryTheirCodeAndNoArguments() {
		assertMessage(ExecutionMessages.inventoryStarted(), ExecutionMessages.INVENTORY_STARTED);
		assertMessage(ExecutionMessages.countingFiles(), ExecutionMessages.COUNTING_FILES);
		assertMessage(ExecutionMessages.processingFiles(), ExecutionMessages.PROCESSING_FILES);
		assertMessage(ExecutionMessages.inventoryCompleted(), ExecutionMessages.INVENTORY_COMPLETED);
		assertMessage(ExecutionMessages.inventoryCancelled(), ExecutionMessages.INVENTORY_CANCELLED);
		assertMessage(ExecutionMessages.progressUpdated(), ExecutionMessages.PROGRESS_UPDATED);
		assertMessage(ExecutionMessages.executionInterrupted(), ExecutionMessages.INTERRUPTED);
		assertMessage(ExecutionMessages.organizationStarted(), ExecutionMessages.ORGANIZATION_STARTED);
		assertMessage(ExecutionMessages.previewStarted(), ExecutionMessages.PREVIEW_STARTED);
	}

	@Test
	void detailFactoriesCarryTheFailureTail() {
		assertMessage(ExecutionMessages.inventoryRejected("locked"), ExecutionMessages.INVENTORY_REJECTED, "locked");
		assertMessage(ExecutionMessages.inventoryFailed("boom"), ExecutionMessages.INVENTORY_FAILED, "boom");
		assertMessage(ExecutionMessages.failed("boom"), ExecutionMessages.FAILED, "boom");
		assertMessage(ExecutionMessages.inventoryStartFailed("bad params"), ExecutionMessages.INVENTORY_START_FAILED,
				"bad params");
		assertMessage(ExecutionMessages.processing("item-1"), ExecutionMessages.PROCESSING_ITEM, "item-1");
		assertMessage(ExecutionMessages.errorProcessingFile("C:/x.jpg"), ExecutionMessages.ERROR_PROCESSING_FILE,
				"C:/x.jpg");
		assertMessage(ExecutionMessages.organizationRejected("locked"), ExecutionMessages.ORGANIZATION_REJECTED,
				"locked");
		assertMessage(ExecutionMessages.organizationFailed("boom"), ExecutionMessages.ORGANIZATION_FAILED, "boom");
	}

	@Test
	void processingFileNormalizesTheAbsolutePath() {
		ExecutionMessage message = ExecutionMessages.processingFile(Path.of("C:/media/photo.jpg"));

		assertThat(message.code()).isEqualTo(ExecutionMessages.PROCESSING_FILE);
		assertThat(message.args()).hasSize(1);
		assertThat((String) message.args().getFirst()).endsWith(Path.of("media", "photo.jpg").toString());
	}

	@Test
	void countFactoriesCarryMovedSkippedAndErrors() {
		assertMessage(ExecutionMessages.organizationFinished(5, 2, 1), ExecutionMessages.ORGANIZATION_FINISHED, 5L, 2L,
				1L);
		assertMessage(ExecutionMessages.previewFinished(3, 0, 0), ExecutionMessages.PREVIEW_FINISHED, 3L, 0L, 0L);
		assertMessage(ExecutionMessages.organizationCancelled(4, 1, 2), ExecutionMessages.ORGANIZATION_CANCELLED, 4L,
				1L, 2L);
	}

	@Test
	void rejectedConflictsCarriesTheConflictCount() {
		assertMessage(ExecutionMessages.rejectedConflicts(7), ExecutionMessages.REJECTED_CONFLICTS, 7L);
	}

	@Test
	void reconcileRepairedCarriesRenamedRepairedAndMarkedMissing() {
		assertMessage(ExecutionMessages.reconcileRepaired(2, 3, 1), ExecutionMessages.RECONCILE_REPAIRED, 2L, 3L, 1L);
	}

	private void assertMessage(ExecutionMessage message, String expectedCode, Object... expectedArgs) {
		assertThat(message.code()).isEqualTo(expectedCode);
		assertThat(message.args()).isEqualTo(List.of(expectedArgs));
	}
}