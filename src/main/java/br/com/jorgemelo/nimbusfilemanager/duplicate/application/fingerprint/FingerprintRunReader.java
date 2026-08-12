package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.execution.application.EtaEstimator;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionStatusNames;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.EtaEstimate;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * Whether a fingerprint backlog is being drained, and how much longer it looks
 * like taking.
 *
 * <p>
 * Both answers used to be fields of the runner, which worked only for as long as
 * the drain happened in the process being asked. It does not: a screen renders
 * in the application and the work happens in the worker, so the question goes to
 * the row. The answer therefore survives a restart of either side, and is the
 * same answer in two open tabs.
 */
@Service
@Transactional(readOnly = true)
public class FingerprintRunReader {

	private final ExecutionRepository executionRepository;
	private final EtaEstimator etaEstimator;

	public FingerprintRunReader(ExecutionRepository executionRepository, EtaEstimator etaEstimator) {
		this.executionRepository = executionRepository;
		this.etaEstimator = etaEstimator;
	}

	public Optional<Execution> running(ExecutionType type) {
		return executionRepository.findFirstByExecutionTypeAndStatusInOrderByStartedAtDesc(type,
				ExecutionStatusNames.ACTIVE);
	}

	public boolean isRunning(ExecutionType type) {
		return running(type).isPresent();
	}

	/**
	 * How much longer this backlog has to go, from the one estimator the
	 * application has.
	 *
	 * <p>
	 * It used to work the answer out here, from elapsed time against the fraction
	 * reached, and it divided by the wrong counter for years: the drain reports the
	 * same running count in {@code filesFound} and {@code filesAnalyzed}, so
	 * dividing by the first divided by the numerator and the remainder came out as
	 * zero on every poll. The panel said "less than a minute" with a hundred
	 * thousand files still to hash.
	 */
	public EtaEstimate eta(ExecutionType type) {
		return running(type).map(etaEstimator::estimate).orElse(EtaEstimate.notApplicable());
	}
}