package br.com.jorgemelo.nimbusfilemanager.telemetry.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.model.ExecutionMetrics;

/**
 * Port for the performance-telemetry rows. {@link ExecutionMetrics} is a
 * unidirectional child of {@code Execution} (it owns the shared-identity
 * association; {@code Execution} deliberately holds no inverse field, so the
 * frequent history/dashboard entity loads never trigger an eager metrics
 * SELECT). This repository is the read/write side that {@code Execution} no
 * longer provides through a cascade.
 */
public interface ExecutionMetricsRepository extends JpaRepository<ExecutionMetrics, Long> {
}