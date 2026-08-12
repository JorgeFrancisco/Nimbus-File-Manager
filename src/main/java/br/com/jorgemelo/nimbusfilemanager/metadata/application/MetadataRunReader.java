package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.execution.application.EtaEstimator;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionStatusNames;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.EtaEstimate;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildPreview;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildResponse;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildSimulation;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.model.MetadataRebuildPreviewItemRecord;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.model.MetadataRebuildPreviewRecord;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.repository.MetadataRebuildPreviewItemRepository;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.repository.MetadataRebuildPreviewRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.application.DateSourceLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.ProgressMath;

/**
 * What the settings screen asks about the metadata rebuild: whether one is
 * running, how far it has got, and what the last one found.
 *
 * <p>
 * All three used to be fields of a runner, which answered only while the pass
 * happened in the process being asked. It does not, so all three are read from
 * the most recent row of this type - one query, because "is one running" and
 * "what did the last one say" are the same question asked of the same run at
 * different moments.
 *
 * <p>
 * The wording of a stored date source happens here rather than when the row was
 * written: the worker has no request behind it and therefore no language, and a
 * preview written under one language must not be read in it.
 */
@Service
@Transactional(readOnly = true)
public class MetadataRunReader extends LocalizedComponent {

	private final ExecutionRepository executionRepository;
	private final EtaEstimator etaEstimator;
	private final MetadataRebuildPreviewRepository metadataRebuildPreviewRepository;
	private final MetadataRebuildPreviewItemRepository metadataRebuildPreviewItemRepository;
	private final ExecutionMessageCodec executionMessageCodec;
	private final DateSourceLabels dateSourceLabels;

	public MetadataRunReader(ExecutionRepository executionRepository, EtaEstimator etaEstimator,
			MetadataRebuildPreviewRepository metadataRebuildPreviewRepository,
			MetadataRebuildPreviewItemRepository metadataRebuildPreviewItemRepository,
			ExecutionMessageCodec executionMessageCodec, DateSourceLabels dateSourceLabels) {
		this.executionRepository = executionRepository;
		this.etaEstimator = etaEstimator;
		this.metadataRebuildPreviewRepository = metadataRebuildPreviewRepository;
		this.metadataRebuildPreviewItemRepository = metadataRebuildPreviewItemRepository;
		this.executionMessageCodec = executionMessageCodec;
		this.dateSourceLabels = dateSourceLabels;
	}

	public boolean isRunning() {
		return latest().filter(execution -> ExecutionStatusNames.ACTIVE.contains(execution.getStatus())).isPresent();
	}

	public long processed() {
		return latest().map(execution -> value(execution.getFilesAnalyzed())).orElse(0L);
	}

	public long total() {
		return latest().map(execution -> value(execution.getTotalExpected())).orElse(0L);
	}

	/** 0-100, or -1 when the total is unknown. */
	public double percent() {
		return ProgressMath.percent(processed(), total());
	}

	/** How much longer, from the one estimator the application has. */
	public EtaEstimate eta() {
		return latest().map(etaEstimator::estimate).orElse(EtaEstimate.notApplicable());
	}

	/**
	 * What the last run reported, or null while one is going on and before any has
	 * ever finished. A failed run reports through {@link #lastError()} instead,
	 * which is the same split the screen already made.
	 */
	public MetadataRebuildResponse lastResult() {
		return latest().filter(execution -> execution.getStatus() != ExecutionStatus.ERROR)
				.filter(execution -> execution.getFinishedAt() != null).map(this::result).orElse(null);
	}

	/** The reason the last run failed, already worded, or null when it did not. */
	public String lastError() {
		return latest().filter(execution -> execution.getStatus() == ExecutionStatus.ERROR).map(this::resolve)
				.orElse(null);
	}

	private Optional<Execution> latest() {
		return executionRepository.findFirstByExecutionTypeOrderByCreatedAtDesc(ExecutionType.METADATA_REBUILD);
	}

	/**
	 * A dry run is told apart by the flag the request carried, not by the presence
	 * of a preview: a simulation that found nothing to change is still a
	 * simulation, and reporting it as a rebuild of zero files would answer a
	 * question nobody asked.
	 */
	private MetadataRebuildResponse result(Execution execution) {
		boolean dryRun = !Boolean.TRUE.equals(execution.getExecuteFlag());

		if (dryRun) {
			return simulationOf(execution);
		}

		return new MetadataRebuildResponse(execution.getSourcePath(), false, count(execution.getFilesFound()),
				count(execution.getFilesMoved()), count(execution.getCacheHits()), 0, 0, count(execution.getErrors()),
				null);
	}

	private MetadataRebuildResponse simulationOf(Execution execution) {
		MetadataRebuildPreviewRecord preview = metadataRebuildPreviewRepository.findByExecutionId(execution.getId())
				.orElse(null);

		if (preview == null) {
			return new MetadataRebuildResponse(execution.getSourcePath(), true, count(execution.getFilesFound()), 0, 0,
					0, 0, 0, null);
		}

		List<MetadataRebuildPreview> rows = metadataRebuildPreviewItemRepository
				.findByExecutionIdOrderByOrdinalAsc(execution.getId()).stream().map(this::row).toList();

		MetadataRebuildSimulation simulation = new MetadataRebuildSimulation(preview.getSkippedByCutoff(),
				preview.getExamined(), preview.getWouldChange(), rows);

		return new MetadataRebuildResponse(preview.getSourcePath(), true, preview.getCandidates(), 0, 0, 0, 0, 0,
				simulation);
	}

	private MetadataRebuildPreview row(MetadataRebuildPreviewItemRecord item) {
		return new MetadataRebuildPreview(item.getPath(), item.getCurrentDate(),
				dateSourceLabels.label(item.getCurrentSource()), item.getNewDate(),
				dateSourceLabels.label(item.getNewSource()));
	}

	private String resolve(Execution execution) {
		if (execution.getStatusMessage() == null) {
			return null;
		}

		if (execution.getStatusMessage().getCode() == null) {
			return execution.getStatusMessage().getText();
		}

		return message(execution.getStatusMessage().getCode(),
				executionMessageCodec.decode(execution.getStatusMessage().getArgs()));
	}

	private long value(Integer count) {
		return count == null ? 0 : count;
	}

	private int count(Integer value) {
		return value == null ? 0 : value;
	}
}