package br.com.jorgemelo.nimbusfilemanager.worker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import br.com.jorgemelo.nimbusfilemanager.worker.application.constants.WorkerExitCodes;

/**
 * Leaving, and the order it has to happen in.
 *
 * <p>
 * A worker told to go must stop claiming before it goes, or the last thing it
 * does on the way out is take one more execution - which then has an owner that
 * is about to not exist. The order is the whole of what is asserted here; the
 * ending itself is a process boundary, and a test that let it happen would end
 * with the JVM running the test.
 */
class WorkerStandDownTest {

	private final WorkerLoop workerLoop = mock(WorkerLoop.class);

	private final List<Integer> exits = new ArrayList<>();

	private final WorkerProcessExit workerProcessExit = exits::add;

	private final WorkerStandDown standDown = new WorkerStandDown(workerLoop, workerProcessExit);

	@Test
	void stopsClaimingBeforeItEnds() {
		standDown.leave("a reason", WorkerExitCodes.PARENT_GONE);

		InOrder order = Mockito.inOrder(workerLoop);

		order.verify(workerLoop).stopAccepting();

		assertThat(exits).containsExactly(WorkerExitCodes.PARENT_GONE);
	}

	@Test
	void carriesTheCodeThroughToWhoeverIsWatching() {
		standDown.leave("an incompatible schema", WorkerExitCodes.SCHEMA_INCOMPATIBLE);

		assertThat(exits).containsExactly(WorkerExitCodes.SCHEMA_INCOMPATIBLE);
	}

	/**
	 * The reasons can arrive together - an application dying mid-upgrade is also
	 * an application whose database is about to be a different schema - and the
	 * second one has nothing left to do.
	 */
	@Test
	void leavesOnlyOnceHoweverManyReasonsArrive() {
		standDown.leave("the first reason", WorkerExitCodes.PARENT_GONE);
		standDown.leave("the second reason", WorkerExitCodes.SCHEMA_INCOMPATIBLE);

		verify(workerLoop, times(1)).stopAccepting();

		assertThat(exits).containsExactly(WorkerExitCodes.PARENT_GONE);
	}

	@Test
	void saysWhetherItIsOnItsWayOut() {
		assertThat(standDown.isLeaving()).isFalse();

		standDown.leave("a reason", WorkerExitCodes.PARENT_GONE);

		assertThat(standDown.isLeaving()).isTrue();
	}
}