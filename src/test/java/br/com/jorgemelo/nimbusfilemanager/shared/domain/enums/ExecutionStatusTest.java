package br.com.jorgemelo.nimbusfilemanager.shared.domain.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExecutionStatusTest {

	@Test
	void terminalStatusesAreTheSixFinalOutcomes() {
		assertThat(ExecutionStatus.FINISHED.isTerminal()).isTrue();
		assertThat(ExecutionStatus.FINISHED_WITH_ERRORS.isTerminal()).isTrue();
		assertThat(ExecutionStatus.INTERRUPTED.isTerminal()).isTrue();
		assertThat(ExecutionStatus.ERROR.isTerminal()).isTrue();
		assertThat(ExecutionStatus.CANCELLED.isTerminal()).isTrue();
		assertThat(ExecutionStatus.REJECTED.isTerminal()).isTrue();
	}

	@Test
	void queuedAndRunningAreNotTerminal() {
		assertThat(ExecutionStatus.PENDING.isTerminal()).isFalse();
		assertThat(ExecutionStatus.RUNNING.isTerminal()).isFalse();
	}
}