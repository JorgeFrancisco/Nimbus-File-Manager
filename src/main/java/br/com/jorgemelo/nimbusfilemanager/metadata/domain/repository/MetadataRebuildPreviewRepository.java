package br.com.jorgemelo.nimbusfilemanager.metadata.domain.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import br.com.jorgemelo.nimbusfilemanager.metadata.domain.model.MetadataRebuildPreviewRecord;

@Repository
public interface MetadataRebuildPreviewRepository extends JpaRepository<MetadataRebuildPreviewRecord, Long> {

	Optional<MetadataRebuildPreviewRecord> findByExecutionId(Long executionId);
}