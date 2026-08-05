package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationPathKey;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * What the Files screen does about a file it found missing: it asks, and the
 * worker repairs. The shape of the request is the whole subject - a reconcile
 * of exactly the folder that was listed, not recursive, because a listing is
 * not recursive either and the two sides of a reconcile have to describe the
 * same universe.
 */
class ExplorerReconcileLauncherTest {

	private final ExecutionEnqueueService executionEnqueueService = mock(ExecutionEnqueueService.class);

	private final ExplorerReconcileLauncher launcher = new ExplorerReconcileLauncher(executionEnqueueService);

	@Test
	void asksForAShallowReconcileOfTheFolderThatWasListed(@TempDir Path folder) {
		launcher.repairFolder(folder);

		ArgumentCaptor<Execution> queued = ArgumentCaptor.captor();

		verify(executionEnqueueService).enqueue(queued.capture());

		assertThat(queued.getValue().getExecutionType()).isEqualTo(ExecutionType.RECONCILE);
		assertThat(queued.getValue().getSourcePath()).isEqualTo(folder.toString());
		assertThat(queued.getValue().getRecursive()).isFalse();
		assertThat(queued.getValue().getDedupKey()).isEqualTo(OperationPathKey.canonical(folder));
	}
}