package br.com.jorgemelo.nimbusfilemanager.conversion.domain.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import br.com.jorgemelo.nimbusfilemanager.conversion.domain.model.ConversionItemResult;

/**
 * The lines of a conversion report, in the order the batch produced them.
 *
 * <p>
 * Nothing removes them: the row belongs to its execution and goes when the
 * execution goes, by the cascade the history retention already relies on.
 */
public interface ConversionItemResultRepository extends JpaRepository<ConversionItemResult, Long> {

	List<ConversionItemResult> findByExecutionIdOrderByIdAsc(Long executionId);
}