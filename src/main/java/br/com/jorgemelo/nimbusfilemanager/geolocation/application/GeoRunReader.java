package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionStatusNames;
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
 * What the settings page asks about the two geographic workloads: whether one is
 * running, how far it got, and what the last one reported.
 *
 * <p>
 * All of it used to be fields of two runners, which answered only while the work
 * happened in the process being asked. It does not, so all of it is read from
 * the most recent row of each type - which is also what lets the page report a
 * run this application never started, and keep reporting it after a restart.
 */
@Service
@Transactional(readOnly = true)
public class GeoRunReader extends LocalizedComponent {

	private final ExecutionRepository executionRepository;
	private final ExecutionMessageCodec executionMessageCodec;
	private final Clock clock;

	public GeoRunReader(ExecutionRepository executionRepository, ExecutionMessageCodec executionMessageCodec,
			Clock clock) {
		this.executionRepository = executionRepository;
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
	public long rebuildEtaSeconds() {
		return latest(ExecutionType.LOCATION_REBUILD).map(this::etaSeconds).orElse(-1L);
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
	 * The level being worked on travels as the first argument of the message,
	 * which is the same place every other execution keeps what its sentence is
	 * about - and it travels as a key, so the page words it.
	 */
	private String stepLabel(Execution execution) {
		if (execution.getStatusMessage() == null || execution.getStatusMessage().getArgs() == null) {
			return "";
		}

		Object[] args = executionMessageCodec.decode(execution.getStatusMessage().getArgs());

		return args.length == 0 ? "" : String.valueOf(args[0]);
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

	private long etaSeconds(Execution execution) {
		if (execution.getStartedAt() == null || execution.getFinishedAt() != null) {
			return -1;
		}

		long elapsed = Duration.between(execution.getStartedAt().atZone(clock.getZone()).toInstant(), clock.instant())
				.toMillis();

		return ProgressMath.etaSeconds(elapsed, value(execution.getFilesAnalyzed()),
				value(execution.getTotalExpected()));
	}

	private long value(Integer count) {
		return count == null ? 0 : count;
	}
}