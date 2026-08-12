package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.execution.application.EtaEstimator;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionStatusNames;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.EtaEstimate;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.constants.GeoConstants;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LocationRebuildResult;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.Snapshot;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationRebuildScope;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.Phase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.EnumUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.ProgressMath;

/**
 * What the settings page asks about the two geographic workloads: whether one
 * is running, how far it got, and what the last one reported.
 *
 * <p>
 * All of it used to be fields of two runners, which answered only while the
 * work happened in the process being asked. It does not, so all of it is read
 * from the most recent row of each type - which is also what lets the page
 * report a run this application never started, and keep reporting it after a
 * restart.
 */
@Service
@Transactional(readOnly = true)
public class GeoRunReader extends LocalizedComponent {

	private final ExecutionRepository executionRepository;
	private final EtaEstimator etaEstimator;
	private final ExecutionMessageCodec executionMessageCodec;
	private final Clock clock;

	public GeoRunReader(ExecutionRepository executionRepository, EtaEstimator etaEstimator,
			ExecutionMessageCodec executionMessageCodec, Clock clock) {
		this.executionRepository = executionRepository;
		this.etaEstimator = etaEstimator;
		this.executionMessageCodec = executionMessageCodec;
		this.clock = clock;
	}

	/**
	 * Whether anything is touching the dataset right now, either kind. What the
	 * destructive actions of the panel - removing the dataset, turning the feature
	 * off, clearing the cache - used to ask two runners, in this process.
	 */
	public boolean busy() {
		return active(ExecutionType.GEO_DATASET_UPDATE) || active(ExecutionType.LOCATION_REBUILD);
	}

	public boolean rebuildRunning() {
		return active(ExecutionType.LOCATION_REBUILD);
	}

	public boolean importRunning() {
		return active(ExecutionType.GEO_DATASET_UPDATE);
	}

	/**
	 * When the dataset was last checked against its source - which is a different
	 * fact from when it was imported, and the only one of the two that a run
	 * finding nothing new produces.
	 */
	public LocalDateTime lastVerifiedAt() {
		return lastCompleted().map(Execution::getFinishedAt).orElse(null);
	}

	/**
	 * Whether a run has already discharged today's obligation to check the dataset.
	 *
	 * <p>
	 * <b>The history is the authority, not the timer's memory.</b> The daily pass
	 * used to remember in a field that a restart cleared, so the first tick after
	 * every restart asked for another update - and with the old behaviour that
	 * meant a second full reimport of every boundary minutes after the first. What
	 * discharges the obligation is a run that finished successfully, and rows
	 * outlive processes.
	 *
	 * <p>
	 * <b>Only {@code FINISHED}, and by {@code finished_at}.</b> A run that was
	 * rejected, cancelled or failed never reached the source, so it owes the day
	 * nothing; one still running has not answered yet. The day is the local one,
	 * from the application clock, because that is the zone the row was written in.
	 * Nothing distinguishes a manual update from the timer's here, and nothing
	 * should: they are the same request, made through the same launcher, doing the
	 * same work.
	 */
	public boolean completedToday() {
		LocalDateTime finishedAt = lastVerifiedAt();

		return finishedAt != null && finishedAt.toLocalDate().equals(LocalDate.now(clock));
	}

	private Optional<Execution> lastCompleted() {
		return executionRepository.findFirstByExecutionTypeAndStatusOrderByFinishedAtDesc(
				ExecutionType.GEO_DATASET_UPDATE, ExecutionStatus.FINISHED);
	}

	public long rebuildProcessed() {
		return latest(ExecutionType.LOCATION_REBUILD).map(execution -> value(execution.getFilesAnalyzed())).orElse(0L);
	}

	public long rebuildTotal() {
		return latest(ExecutionType.LOCATION_REBUILD).map(execution -> value(execution.getTotalExpected())).orElse(0L);
	}

	/** 0-100, or -1 when the total is unknown. */
	public double rebuildPercent() {
		return ProgressMath.percent(rebuildProcessed(), rebuildTotal());
	}

	/** Estimated seconds remaining by average rate, or -1 when unknown. */
	/** How much longer, from the one estimator the application has. */
	public EtaEstimate rebuildEta() {
		return latest(ExecutionType.LOCATION_REBUILD).map(etaEstimator::estimate).orElse(EtaEstimate.notApplicable());
	}

	/**
	 * What the last rebuild reported, or null while one is running and before any
	 * has finished. A failed one reports through {@link #rebuildError()} instead,
	 * which is the split the panel already made.
	 */
	public LocationRebuildResult lastRebuildResult() {
		return finished(ExecutionType.LOCATION_REBUILD).map(this::rebuildResult).orElse(null);
	}

	public String rebuildError() {
		return errorOf(ExecutionType.LOCATION_REBUILD);
	}

	public String importError() {
		return errorOf(ExecutionType.GEO_DATASET_UPDATE);
	}

	/**
	 * Which level the update is on and how far into it, out of the phase and the
	 * message the run wrote. Idle when nothing is running, which is what the panel
	 * shows for every other state.
	 */
	public Snapshot progress() {
		return latest(ExecutionType.GEO_DATASET_UPDATE).filter(this::isActive).map(this::snapshot)
				.orElseGet(Snapshot::idle);
	}

	private Snapshot snapshot(Execution execution) {
		Phase phase = execution.getPhase() == ExecutionPhase.PROCESSING ? Phase.IMPORTING : Phase.DOWNLOADING;

		Integer percent = execution.getCurrentItemPercent();

		return new Snapshot(phase, stepLabel(execution), percent == null ? -1 : percent);
	}

	/**
	 * The level being worked on is the last segment of the message code, and the
	 * panel words it from its own bundle.
	 *
	 * <p>
	 * It used to be the message's first argument. That stopped being true when the
	 * level moved into the code - an argument holding the key of another message is
	 * never resolved on the way out, so the row said "Baixando
	 * settings.geo.step.country." - and this method went on reading an argument
	 * that was no longer there, handing the page an empty key. The page asked its
	 * bundle for it and drew what a missing key looks like: {@code ??_pt_BR??}.
	 *
	 * <p>
	 * Stages that are not about one level - the territories, the publication, the
	 * finish - answer with the dataset itself, so the sentence stays whole instead
	 * of trailing off after the verb.
	 */
	private String stepLabel(Execution execution) {
		String code = execution.getStatusMessage() == null ? null : execution.getStatusMessage().getCode();

		if (code == null) {
			return GeoConstants.STEP_DATASET;
		}

		return switch (code.substring(code.lastIndexOf('.') + 1)) {
		case "country" -> GeoConstants.STEP_COUNTRY;
		case "state" -> GeoConstants.STEP_STATE;
		case "municipality" -> GeoConstants.STEP_MUNICIPALITY;
		default -> GeoConstants.STEP_DATASET;
		};
	}

	private LocationRebuildResult rebuildResult(Execution execution) {
		return new LocationRebuildResult(scopeOf(execution), value(execution.getFilesFound()),
				value(execution.getFilesMoved()), value(execution.getCacheHits()), value(execution.getErrors()));
	}

	/**
	 * The scope the run was asked with, kept on the row as its deduplication key -
	 * so the panel can say which rebuild the numbers belong to without the payload
	 * being read again.
	 */
	private LocationRebuildScope scopeOf(Execution execution) {
		return EnumUtils.valueOfOrDefault(LocationRebuildScope.class, execution.getDedupKey(),
				LocationRebuildScope.PENDING);
	}

	private String errorOf(ExecutionType type) {
		return latest(type).filter(execution -> execution.getStatus() == ExecutionStatus.ERROR).map(this::resolve)
				.orElse(null);
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

	private Optional<Execution> finished(ExecutionType type) {
		return latest(type).filter(execution -> execution.getStatus() != ExecutionStatus.ERROR)
				.filter(execution -> execution.getFinishedAt() != null);
	}

	private Optional<Execution> latest(ExecutionType type) {
		return executionRepository.findFirstByExecutionTypeOrderByCreatedAtDesc(type);
	}

	private boolean active(ExecutionType type) {
		return latest(type).filter(this::isActive).isPresent();
	}

	private boolean isActive(Execution execution) {
		return ExecutionStatusNames.ACTIVE.contains(execution.getStatus());
	}

	private long value(Integer count) {
		return count == null ? 0 : count;
	}
}