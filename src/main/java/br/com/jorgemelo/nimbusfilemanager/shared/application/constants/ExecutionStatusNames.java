package br.com.jorgemelo.nimbusfilemanager.shared.application.constants;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;

/**
 * The single definition of the statuses an execution is in while its background
 * thread is still running - derived from {@link ExecutionStatus#isTerminal()}
 * (the non-terminal states), so adding a status never needs editing this set
 * too. {@link #IN_PROGRESS_NAMES} is the same set as the stored/serialized
 * status names, for the controllers that compare the string status of an
 * {@code ExecutionResponse} (the dashboard refresh cadence and the
 * execution-detail redirect to the live progress screen).
 */
public final class ExecutionStatusNames {

	public static final Set<ExecutionStatus> IN_PROGRESS = Arrays.stream(ExecutionStatus.values())
			.filter(status -> !status.isTerminal()).collect(Collectors.toUnmodifiableSet());

	public static final Set<String> IN_PROGRESS_NAMES = IN_PROGRESS.stream().map(Enum::name)
			.collect(Collectors.toUnmodifiableSet());

	private ExecutionStatusNames() {
	}
}
