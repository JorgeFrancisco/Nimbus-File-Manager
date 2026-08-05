package br.com.jorgemelo.nimbusfilemanager.metadata.domain.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import br.com.jorgemelo.nimbusfilemanager.metadata.domain.model.MetadataRebuildPreviewItemId;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.model.MetadataRebuildPreviewItemRecord;

@Repository
public interface MetadataRebuildPreviewItemRepository
		extends JpaRepository<MetadataRebuildPreviewItemRecord, MetadataRebuildPreviewItemId> {

	List<MetadataRebuildPreviewItemRecord> findByExecutionIdOrderByOrdinalAsc(Long executionId);

	void deleteByExecutionId(Long executionId);
}