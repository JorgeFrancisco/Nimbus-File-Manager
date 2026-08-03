package br.com.jorgemelo.nimbusfilemanager.execution.domain.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import br.com.jorgemelo.nimbusfilemanager.execution.domain.model.ExecutionStep;

public interface ExecutionStepRepository extends JpaRepository<ExecutionStep, Long> {

	List<ExecutionStep> findByExecutionIdOrderByCreatedAtAsc(Long executionId);
}