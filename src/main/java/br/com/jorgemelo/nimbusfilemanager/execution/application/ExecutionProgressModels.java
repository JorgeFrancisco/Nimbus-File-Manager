package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionProgressModel;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ProgressDone;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ProgressUnit;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;

/**
 * What every workload's progress counters mean, declared in one place.
 *
 * <p>
 * <b>Why a table and not a guess.</b> Every generic reader of an execution row -
 * the progress screen, the activity banner, the estimate - needs to know which
 * counter is "done". None of them can know, because the answer differs per
 * workload and lives nowhere: it is decided by the order of the arguments a job
 * handler passes when it reports. So they guessed the same field, and the guess
 * was wrong for three workloads at once. This is the declaration they were
 * missing.
 *
 * <p>
 * <b>The default is the historical guess, deliberately.</b> A type absent from
 * the table keeps reading {@code filesFound}, which is what every reader did
 * before this class existed, so adding it changed nothing for the workloads
 * nobody had audited. Being explicit here is what an audit produces; silence
 * means "not yet examined", never "examined and found ordinary".
 */
@Component
public class ExecutionProgressModels {

	private static final ExecutionProgressModel DEFAULT = ExecutionProgressModel.items(ProgressDone.FILES_FOUND);

	private final Map<ExecutionType, ExecutionProgressModel> models = new EnumMap<>(ExecutionType.class);

	public ExecutionProgressModels() {
		// The drain reports the same running count in both counters and keeps failures
		// apart; a file it gave up on is behind the drain, not ahead of it.
		files(ProgressDone.ANALYZED_AND_FAILED, ExecutionType.FINGERPRINT_PHOTO, ExecutionType.FINGERPRINT_VIDEO);

		// An inventory's first counter is discovery, which runs ahead of analysis - it
		// filled the bar before the work was done. The three outcomes an item can reach
		// are what "concluded" means here.
		files(ProgressDone.ANALYZED_CACHED_AND_FAILED, ExecutionType.INVENTORY, ExecutionType.RECONCILE);

		// These report the concluded count in the second counter and something else in
		// the first: a metadata rebuild leaves the first at a constant zero, which is
		// why its bar never moved off 0% on the executions screen while the settings
		// screen showed the truth.
		files(ProgressDone.FILES_ANALYZED, ExecutionType.METADATA_REBUILD, ExecutionType.LOCATION_REBUILD);

		// The first counter holds the concluded count and the second the subset that
		// actually moved, so the bar has to read the first.
		items(ProgressDone.FILES_FOUND, ExecutionType.ORGANIZATION, ExecutionType.ORGANIZATION_PREVIEW,
				ExecutionType.UNDO, ExecutionType.DEDUP_DELETE);

		// Counted in hundredths because one video can hold the run for hours: over
		// whole files the bar would not move for all that time, and the estimate would
		// have nothing to divide by until the first one finished.
		put(new ExecutionProgressModel(ProgressDone.ANALYZED_WITH_ITEM_PERCENT, ProgressUnit.HUNDREDTHS, true),
				ExecutionType.CONVERSION);

		// A similarity analysis reports the *total* in the first counter, so reading it
		// drew a full bar before a single pair had been compared. And its cost grows
		// with the pairs compared rather than the files walked, so no rate over files
		// predicts its end.
		withoutEstimate(ProgressDone.FILES_ANALYZED, ProgressUnit.FILES, ExecutionType.SIMILARITY_PHOTO,
				ExecutionType.SIMILARITY_VIDEO);

		// Nine stages whose costs run from a one-second ETag check to a three-minute
		// import of fifty thousand municipalities. The bar over stages is honest; a
		// single rate over them would not be.
		withoutEstimate(ProgressDone.FILES_ANALYZED, ProgressUnit.STAGES, ExecutionType.GEO_DATASET_UPDATE);

		// One item, so there is no population whose end could be predicted.
		withoutEstimate(ProgressDone.FILES_FOUND, ProgressUnit.ITEMS, ExecutionType.CONTENT_VERIFICATION);
	}

	public ExecutionProgressModel modelFor(ExecutionType type) {
		return type == null ? DEFAULT : models.getOrDefault(type, DEFAULT);
	}

	private void files(ProgressDone done, ExecutionType... types) {
		put(ExecutionProgressModel.files(done), types);
	}

	private void items(ProgressDone done, ExecutionType... types) {
		put(ExecutionProgressModel.items(done), types);
	}

	private void withoutEstimate(ProgressDone done, ProgressUnit unit, ExecutionType... types) {
		put(ExecutionProgressModel.withoutEstimate(done, unit), types);
	}

	private void put(ExecutionProgressModel model, ExecutionType... types) {
		for (ExecutionType type : types) {
			models.put(type, model);
		}
	}
}