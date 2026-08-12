package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionAdjustments;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionFileResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionProgress;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.model.ConversionItemResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.repository.ConversionItemResultRepository;
import br.com.jorgemelo.nimbusfilemanager.execution.application.EtaEstimator;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressReader;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.EtaEstimate;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.NumberUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.SizeFormatter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

/**
 * What the conversion screen asks while a batch runs, and afterwards.
 *
 * <p>
 * Every number here comes from the row and from the lines the batch wrote as it
 * went. It used to come from counters in the object doing the encoding, which
 * answered only for the process holding them - and the process holding them
 * stopped being the one serving the screen.
 *
 * <p>
 * The two-level bar survives that move: how many videos are done is the
 * execution's own counter, and how far into the one running is the column the
 * worker updates as ffmpeg reports.
 */
@Service
public class ConversionProgressService {

	/** Nothing has ever run, so there is nothing to report and nothing running. */
	private static final ConversionProgress IDLE = new ConversionProgress(false, 0, 0, 0, 0,
			EtaEstimate.notApplicable(), null, null);

	private final ExecutionRepository executionRepository;
	private final ConversionItemResultRepository conversionItemResultRepository;
	private final ExecutionMessageCodec executionMessageCodec;
	private final EtaEstimator etaEstimator;
	private final ExecutionProgressReader progressReader;

	public ConversionProgressService(ExecutionRepository executionRepository,
			ConversionItemResultRepository conversionItemResultRepository, ExecutionMessageCodec executionMessageCodec,
			EtaEstimator etaEstimator, ExecutionProgressReader progressReader) {
		this.executionRepository = executionRepository;
		this.conversionItemResultRepository = conversionItemResultRepository;
		this.executionMessageCodec = executionMessageCodec;
		this.etaEstimator = etaEstimator;
		this.progressReader = progressReader;
	}

	public ConversionProgress snapshot() {
		return latest().map(this::snapshotOf).orElse(IDLE);
	}

	/**
	 * Whether a batch is going on right now - which the screen asks to decide
	 * whether to offer the button at all.
	 */
	public boolean running() {
		return latest().map(execution -> !execution.getStatus().isTerminal()).orElse(false);
	}

	public Optional<Execution> active() {
		return latest().filter(execution -> !execution.getStatus().isTerminal());
	}

	private Optional<Execution> latest() {
		return executionRepository.findFirstByExecutionTypeOrderByCreatedAtDesc(ExecutionType.CONVERSION);
	}

	private ConversionProgress snapshotOf(Execution execution) {
		if (execution.getStatus().isTerminal()) {
			return new ConversionProgress(false, 0, 0, 0, 0, EtaEstimate.notApplicable(), null, reportOf(execution));
		}

		int total = NumberUtils
				.toInt(execution.getTotalExpected() == null ? execution.getFilesFound() : execution.getTotalExpected());
		int processed = NumberUtils.toInt(execution.getFilesAnalyzed());
		// Null until the encoder reports for the first time, which is the state every
		// batch is in for its first seconds - and the screen polls from the first one.
		int filePercent = execution.getCurrentItemPercent() == null ? 0 : execution.getCurrentItemPercent();

		return new ConversionProgress(true, processed, total, percentOf(execution), filePercent,
				etaEstimator.estimate(execution), currentFileOf(execution), null);
	}

	/**
	 * From the same reader every other screen uses, over the hundredths this
	 * workload declares: a batch of one long video advances visibly because the
	 * model counts hundredths, not because this class counts them for itself.
	 */
	private double percentOf(Execution execution) {
		Double percent = progressReader.percent(execution);

		return percent == null ? 0 : percent;
	}

	private String currentFileOf(Execution execution) {
		if (execution.getStatusMessage() == null || execution.getStatusMessage().getArgs() == null) {
			return null;
		}

		Object[] args = executionMessageCodec.decode(execution.getStatusMessage().getArgs());

		return args.length == 0 || args[0] == null ? null : args[0].toString();
	}

	/**
	 * The report the screen shows when the batch ends, rebuilt from the lines it
	 * wrote. The totals are added up here rather than stored again: a column
	 * holding the sum of other rows is a column that can disagree with them.
	 */
	private ConversionResult reportOf(Execution execution) {
		List<ConversionItemResult> items = conversionItemResultRepository
				.findByExecutionIdOrderByIdAsc(execution.getId());

		long originalBytes = items.stream().mapToLong(ConversionItemResult::getOriginalBytes).sum();
		long convertedBytes = items.stream().mapToLong(ConversionItemResult::getConvertedBytes).sum();
		long savedBytes = originalBytes - convertedBytes;

		return new ConversionResult(true, NumberUtils.toInt(execution.getFilesFound()),
				NumberUtils.toInt(execution.getFilesMoved()), NumberUtils.toInt(execution.getCacheHits()),
				NumberUtils.toInt(execution.getErrors()), originalBytes, convertedBytes, savedBytes,
				SizeFormatter.format(Math.max(0, savedBytes)), savedPercent(originalBytes, savedBytes),
				UuidV7.orLegacy(execution.getExecutionPublicId(), execution.getId()), messageOf(execution),
				items.stream().map(this::toFileResult).toList());
	}

	private String messageOf(Execution execution) {
		return execution.getStatusMessage() == null ? null : execution.getStatusMessage().getText();
	}

	private int savedPercent(long originalBytes, long savedBytes) {
		return originalBytes <= 0 ? 0 : NumberUtils.toInt(savedBytes * 100 / originalBytes);
	}

	private ConversionFileResult toFileResult(ConversionItemResult item) {
		long savedBytes = item.getOriginalBytes() - item.getConvertedBytes();

		return new ConversionFileResult(item.getCatalogFilePublicId(), item.getFileName(), item.getOutcome(),
				item.getOriginalBytes(), item.getConvertedBytes(), savedBytes,
				SizeFormatter.format(Math.max(0, savedBytes)), 0,
				new ConversionAdjustments(Boolean.TRUE.equals(item.getAudioFallback()),
						Boolean.TRUE.equals(item.getSubtitlesDropped()), Boolean.TRUE.equals(item.getDataDropped())),
				// The history does not record whether the conversion revived an entry: it is
				// read by the batch while it runs and by nobody afterwards.
				Boolean.TRUE.equals(item.getOriginalQuarantined()), false, item.getMessage());
	}
}