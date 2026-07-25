package br.com.jorgemelo.nimbusfilemanager.execution.application;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ExecutionCancellationServiceTest {

	@Test
	void isCancelledShouldBeFalseForAnUnregisteredExecution() {
		ExecutionCancellationService service = new ExecutionCancellationService();

		Assertions.assertThat(service.isCancelled(1L)).isFalse();
	}

	@Test
	void requestCancellationShouldReturnFalseWhenExecutionIsNotRegistered() {
		ExecutionCancellationService service = new ExecutionCancellationService();

		Assertions.assertThat(service.requestCancellation(1L)).isFalse();
	}

	@Test
	void requestCancellationShouldFlagARegisteredExecutionAndReturnTrue() {
		ExecutionCancellationService service = new ExecutionCancellationService();

		service.register(1L);

		Assertions.assertThat(service.isCancelled(1L)).isFalse();
		Assertions.assertThat(service.requestCancellation(1L)).isTrue();
		Assertions.assertThat(service.isCancelled(1L)).isTrue();
	}

	@Test
	void unregisterShouldForgetTheExecutionSoCancellationNoLongerApplies() {
		ExecutionCancellationService service = new ExecutionCancellationService();

		service.register(1L);
		service.unregister(1L);

		Assertions.assertThat(service.requestCancellation(1L)).isFalse();
		Assertions.assertThat(service.isCancelled(1L)).isFalse();
	}

	@Test
	void registeringTwoExecutionsShouldTrackThemIndependently() {
		ExecutionCancellationService service = new ExecutionCancellationService();

		service.register(1L);
		service.register(2L);
		service.requestCancellation(1L);

		Assertions.assertThat(service.isCancelled(1L)).isTrue();
		Assertions.assertThat(service.isCancelled(2L)).isFalse();
	}

	@Test
	void methodsShouldTolerateNullExecutionIdWithoutThrowing() {
		ExecutionCancellationService service = new ExecutionCancellationService();

		service.register(null);
		service.unregister(null);

		Assertions.assertThat(service.isCancelled(null)).isFalse();
		Assertions.assertThat(service.requestCancellation(null)).isFalse();
		Assertions.assertThat(service.isLive(null)).isFalse();
	}

	/**
	 * Liveness is what keeps a genuinely-running execution from being marked
	 * INTERRUPTED: it holds only while the background thread still owns the id.
	 */
	@Test
	void isLiveShouldTrackOnlyExecutionsWhoseThreadIsStillRegistered() {
		ExecutionCancellationService service = new ExecutionCancellationService();

		Assertions.assertThat(service.isLive(1L)).isFalse();

		service.register(1L);

		Assertions.assertThat(service.isLive(1L)).isTrue();

		service.requestCancellation(1L);

		Assertions.assertThat(service.isLive(1L)).isTrue();

		service.unregister(1L);

		Assertions.assertThat(service.isLive(1L)).isFalse();
	}

	@Test
	void requestAllCancellationsShouldFlagEveryRegisteredExecutionAndReturnHowMany() {
		ExecutionCancellationService service = new ExecutionCancellationService();

		service.register(1L);
		service.register(2L);

		Assertions.assertThat(service.requestAllCancellations()).isEqualTo(2);
		Assertions.assertThat(service.isCancelled(1L)).isTrue();
		Assertions.assertThat(service.isCancelled(2L)).isTrue();
	}

	@Test
	void requestAllCancellationsShouldReportZeroWhenNothingIsRunning() {
		ExecutionCancellationService service = new ExecutionCancellationService();

		Assertions.assertThat(service.requestAllCancellations()).isZero();
	}
}