package br.com.jorgemelo.nimbusfilemanager.execution.application.constants;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;

/**
 * The statuses an execution can still move on from - derived from
 * {@link ExecutionStatus#isTerminal()}, so adding a status never needs editing
 * this set too. {@link #ACTIVE_NAMES} is the same set as the stored/serialized
 * names, for the controllers that compare the string status of an
 * {@code ExecutionResponse} (the dashboard refresh cadence and the
 * execution-detail redirect to the live progress screen).
 *
 * <p>
 * "Active" here means the screen still has something to show, which includes an
 * execution nobody has taken yet. Whoever needs "a worker is holding this right
 * now" wants {@link ExecutionStatus#RUNNING} alone - recovery in particular,
 * since a PENDING row surviving a restart is the point of keeping the queue in
 * the database.
 */
public final class ExecutionStatusNames {

	public static final Set<ExecutionStatus> ACTIVE = Arrays.stream(ExecutionStatus.values())
			.filter(status -> !status.isTerminal()).collect(Collectors.toUnmodifiableSet());

	public static final Set<String> ACTIVE_NAMES = ACTIVE.stream().map(Enum::name)
			.collect(Collectors.toUnmodifiableSet());

	private ExecutionStatusNames() {
	}
}