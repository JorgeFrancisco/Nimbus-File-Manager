package br.com.jorgemelo.nimbusfilemanager.telemetry.domain.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.ExecutionPhase;

public interface ExecutionPhaseRepository extends JpaRepository<ExecutionPhase, Long> {

	List<ExecutionPhase> findByExecutionIdOrderByPhaseAsc(Long executionId);

	List<ExecutionPhase> findByExecutionIdInOrderByExecutionIdAscPhaseAsc(Collection<Long> executionIds);
}