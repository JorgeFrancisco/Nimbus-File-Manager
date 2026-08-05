package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataDateDifference;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildSimulationResult;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.model.MetadataRebuildPreviewItemRecord;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.model.MetadataRebuildPreviewRecord;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.repository.MetadataRebuildPreviewItemRepository;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.repository.MetadataRebuildPreviewRepository;

/**
 * Writes what a dry run found where another process can read it.
 *
 * <p>
 * There is no publication protocol to speak of, and none is needed: the reading
 * only ever asks for the preview of a finished execution, so the execution's own
 * status is what makes these rows visible. A retry of the same execution
 * replaces them, which is why the items are cleared first - a second attempt
 * that found fewer differences must not leave the first attempt's tail behind.
 *
 * <p>
 * This is worker-side code. The application reads previews; it does not produce
 * them.
 */
@Component
class MetadataRebuildPreviewWriter {

	private final MetadataRebuildPreviewRepository metadataRebuildPreviewRepository;
	private final MetadataRebuildPreviewItemRepository metadataRebuildPreviewItemRepository;

	MetadataRebuildPreviewWriter(MetadataRebuildPreviewRepository metadataRebuildPreviewRepository,
			MetadataRebuildPreviewItemRepository metadataRebuildPreviewItemRepository) {
		this.metadataRebuildPreviewRepository = metadataRebuildPreviewRepository;
		this.metadataRebuildPreviewItemRepository = metadataRebuildPreviewItemRepository;
	}

	@Transactional
	void write(Long executionId, String sourcePath, MetadataRebuildSimulationResult result) {
		metadataRebuildPreviewItemRepository.deleteByExecutionId(executionId);

		metadataRebuildPreviewRepository.save(MetadataRebuildPreviewRecord.builder().executionId(executionId)
				.sourcePath(sourcePath).candidates(result.candidates()).skippedByCutoff(result.skippedByCutoff())
				.examined(result.examined()).wouldChange(result.wouldChange()).build());

		List<MetadataRebuildPreviewItemRecord> items = new ArrayList<>(result.differences().size());

		int ordinal = 0;

		for (MetadataDateDifference difference : result.differences()) {
			items.add(MetadataRebuildPreviewItemRecord.builder().executionId(executionId).ordinal(ordinal++)
					.path(difference.path()).currentDate(difference.currentDate())
					.currentSource(difference.currentSource()).newDate(difference.newDate())
					.newSource(difference.newSource()).build());
		}

		metadataRebuildPreviewItemRepository.saveAll(items);
	}
}