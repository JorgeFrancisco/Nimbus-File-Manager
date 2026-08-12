package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.constants.ConversionConstants;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.constants.ConversionMessages;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.CommitResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionAdjustments;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionFileResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionOptions;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionRun;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionTotals;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.TranscodeRequest;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.TranscodeResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionFailure;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionOutcome;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.repository.ConversionCandidateRepository;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.repository.projection.ConversionSource;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.EligibilityAnnouncer;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.ResolvedMediaDate;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.ExtensionUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PhysicalFilePolicy;
import br.com.jorgemelo.nimbusfilemanager.shared.util.SizeFormatter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;
import lombok.extern.slf4j.Slf4j;

/**
 * Converts the videos the user selected on the Conversão screen to H.265, one
 * at a time, as a {@code CONVERSION} execution.
 *
 * <p>
 * The safety contract is the whole point of this class: a file is only ever
 * replaced after its conversion has been encoded, probed and validated, and the
 * original is only ever quarantined after the converted file is already in the
 * library. Anything that fails - the encode, the validation, the move - leaves
 * the original untouched where it was and is counted as an error, never as a
 * conversion. Files are processed sequentially because an H.265 encode already
 * uses every core.
 */
@Slf4j
@Service
public class VideoConversionService extends LocalizedComponent {

	private final CatalogFileRepository catalogFileRepository;
	private final ConversionCandidateRepository conversionCandidateRepository;
	private final VideoTranscoder videoTranscoder;
	private final ConversionCommitService conversionCommitService;
	private final ConversionExecutionRecorder conversionExecutionRecorder;
	private final ExecutionProgressService executionProgressService;
	private final OperationLockService operationLockService;
	private final ExecutionCancellationService executionCancellationService;
	private final EligibilityAnnouncer eligibilityAnnouncer;

	public VideoConversionService(CatalogFileRepository catalogFileRepository,
			ConversionCandidateRepository conversionCandidateRepository, VideoTranscoder videoTranscoder,
			ConversionCommitService conversionCommitService, ConversionExecutionRecorder conversionExecutionRecorder,
			ExecutionProgressService executionProgressService, OperationLockService operationLockService,
			ExecutionCancellationService executionCancellationService, EligibilityAnnouncer eligibilityAnnouncer) {
		this.catalogFileRepository = catalogFileRepository;
		this.conversionCandidateRepository = conversionCandidateRepository;
		this.videoTranscoder = videoTranscoder;
		this.conversionCommitService = conversionCommitService;
		this.conversionExecutionRecorder = conversionExecutionRecorder;
		this.executionProgressService = executionProgressService;
		this.operationLockService = operationLockService;
		this.executionCancellationService = executionCancellationService;
		this.eligibilityAnnouncer = eligibilityAnnouncer;
	}

	/**
	 * Converts the batch a worker claimed.
	 *
	 * <p>
	 * The execution is handed in rather than opened here: the row already exists,
	 * reserved with a lease, and opening a second one would give the same batch two
	 * identities. The ownership travels with it because the one thing that must not
	 * happen is a converted file entering the library from a process that has lost
	 * the right to write there.
	 */
	public ConversionResult convert(Collection<UUID> publicIds, ConversionOptions options, Execution execution,
			ExecutionOwnership ownership) {
		ConversionOptions effective = options == null ? ConversionOptions.defaults() : options;

		if (publicIds == null || publicIds.isEmpty()) {
			return ConversionResult.empty(message("backend.conversion.noneSelected"));
		}

		Path quarantineRoot = null;

		if (effective.quarantinesOriginal()) {
			Optional<Path> configured = conversionCommitService.quarantineRoot();

			if (configured.isEmpty()) {
				return ConversionResult.refused(message("backend.conversion.quarantineNotConfigured"));
			}

			quarantineRoot = configured.get();
		}

		List<CatalogFile> files = catalogFileRepository.findByPublicIdIn(publicIds);

		executionProgressService.updateTotal(ownership, publicIds.size());

		// The batch waits rather than refusing on the spot: what it collides with is
		// almost always background maintenance that finishes on its own, and the user
		// has no way to see it - being told "busy" for that reads as a broken button.
		try (var _ = operationLockService.acquireWithin(ConversionConstants.LOCK_WAIT, ExecutionType.CONVERSION,
				lockedPaths(files, quarantineRoot))) {
			return convertLocked(publicIds, files, effective, quarantineRoot, execution, ownership);
		} catch (OperationLockException lockError) {
			log.warn("Conversion blocked after waiting {}: another operation is using one of its paths: {}",
					ConversionConstants.LOCK_WAIT, lockError.getMessage());

			return ConversionResult.empty(message("backend.conversion.locked"));
		}
	}

	private ConversionResult convertLocked(Collection<UUID> publicIds, List<CatalogFile> files,
			ConversionOptions options, Path quarantineRoot, Execution execution, ExecutionOwnership ownership) {
		// Declaring the id is how the rest of this process learns the batch is alive.
		// The durable answer is the lease on the row; this is what keeps the local
		// cancellation cache honest while the loop below runs.
		BooleanSupplier stopRequested = () -> executionCancellationService.isCancelled(execution.getId());

		try {
			return convertRegistered(execution, publicIds, files, options, quarantineRoot, ownership, stopRequested);
		} catch (RuntimeException conversionError) {
			conversionExecutionRecorder.fail(ownership, conversionError.getMessage());

			throw conversionError;
		} finally {
			executionCancellationService.forget(execution.getId());
		}
	}

	private ConversionResult convertRegistered(Execution execution, Collection<UUID> publicIds, List<CatalogFile> files,
			ConversionOptions options, Path quarantineRoot, ExecutionOwnership ownership, BooleanSupplier cancelled) {
		ConversionRun run = new ConversionRun(execution, ownership, cancelled, quarantineRoot, options);

		int total = publicIds.size();

		Map<UUID, ConversionSource> sources = sourcesById(publicIds);

		List<ConversionFileResult> items = new ArrayList<>(files.size());

		// Ids that resolve to no catalog entry never reach the loop, so they are
		// counted up front: converted + skipped + errors always equals what the user
		// asked for.
		int skipped = total - files.size();
		int converted = 0;
		int errors = 0;
		int processed = 0;

		long originalBytes = 0;
		long convertedBytes = 0;

		for (CatalogFile file : files) {
			// Checked before each file so a cancelled batch stops at the next boundary even
			// when the current encode was already finished.
			if (cancelled.getAsBoolean()) {
				break;
			}

			reportFileStarted(ownership, processed, converted, skipped, errors, file.getFileName());

			ConversionFileResult item = convertOne(run, file, sources.get(file.getPublicId()),
					percent -> executionProgressService.updateCurrentItem(ownership, percent));

			items.add(item);

			conversionExecutionRecorder.recordItem(execution, item);

			switch (item.outcome()) {
			case CONVERTED -> {
				converted++;
				originalBytes += item.originalBytes();
				convertedBytes += item.convertedBytes();
			}
			case SKIPPED -> skipped++;
			case FAILED -> {
				errors++;

				conversionExecutionRecorder.recordFailure(execution, PathUtils.normalizePath(file.getFileKey()),
						item.message());
			}
			case CANCELLED -> log.info("Conversion cancelled while converting {}", file.getFileName());
			}

			processed++;

			// Items concluded, never the batch size: the counter is what the global bar
			// divides by the total, so writing the total here read a hundred per cent
			// from the first file on - and the next file's report, which counts
			// properly, then took the bar backwards. Same expression as
			// reportFileStarted, because one execution cannot have two opinions about
			// how far along it is.
			executionProgressService.updateLiveProgress(ownership, converted + skipped + errors, processed, skipped,
					errors, ExecutionMessages.processing(file.getFileName()));
		}

		// Once for the batch, whatever it did and however many files it did it to. Two
		// things here change who may be analysed and they are independent: an original
		// going to quarantine leaves the analysed set, and a converted file landing on
		// a path the catalog had given up on brings that entry back into it. Either is
		// enough, both together are still one request, and a batch where neither
		// happened asks for nothing.
		//
		// What is deliberately not counted is the ordinary case: a converted file that
		// is new to the catalog has no fingerprint yet, so it joins the analysis
		// through the backlog as an arrival, which is a different request.
		if (items.stream().anyMatch(item -> item.originalQuarantined() || item.revivedCatalogEntry())) {
			eligibilityAnnouncer.announce("video conversion");
		}

		long savedBytes = originalBytes - convertedBytes;

		ConversionTotals totals = new ConversionTotals(total, converted, skipped, errors, originalBytes, convertedBytes,
				savedBytes);

		boolean stopped = cancelled.getAsBoolean();

		ExecutionMessage outcome = stopped ? ConversionMessages.cancelledBatch(converted, errors)
				: ConversionMessages.completed(converted, skipped, errors,
						SizeFormatter.format(Math.max(0, savedBytes)));

		conversionExecutionRecorder.finish(ownership, totals, outcome, stopped);

		String message = resolve(outcome);

		return new ConversionResult(true, total, converted, skipped, errors, originalBytes, convertedBytes, savedBytes,
				SizeFormatter.format(Math.max(0, savedBytes)), savedPercent(originalBytes, savedBytes),
				UuidV7.orLegacy(execution.getPublicId(), execution.getId()), message, List.copyOf(items));
	}

	/**
	 * Says which file the batch has reached, and clears the per-item bar so the
	 * second level starts from zero rather than from where the last encode ended.
	 */
	private void reportFileStarted(ExecutionOwnership ownership, int processed, int converted, int skipped,
			int errors, String fileName) {
		executionProgressService.updateLiveProgress(ownership, converted + skipped + errors, processed, skipped,
				errors, ExecutionMessages.processing(fileName));

		executionProgressService.startsCurrentItem(ownership);
	}

	private ConversionFileResult convertOne(ConversionRun run, CatalogFile file, ConversionSource source,
			IntConsumer onFilePercent) {
		Optional<String> ineligible = ineligibilityOf(file, source);

		if (ineligible.isPresent()) {
			return skipped(file, ineligible.get());
		}

		Path path = PathUtils.normalizePath(file.getFileKey());

		Double duration = source == null ? null : source.durationSeconds();

		TranscodeResult transcode = videoTranscoder.transcode(
				new TranscodeRequest(path, duration, run.options(), isHevc(source)), onFilePercent, run.cancelled());

		if (transcode.failure() == ConversionFailure.CANCELLED) {
			return cancelledItem(file);
		}

		if (!transcode.successful()) {
			return failed(file, transcode);
		}

		CommitResult commit = conversionCommitService.commit(run.execution(), file, transcode.output(),
				run.quarantineRoot(), run.options(), resolvedDateOf(source), run.ownership());

		if (!commit.successful()) {
			return failed(file, TranscodeResult.failed(commit.failure(), transcode.audioFallback(),
					transcode.subtitlesDropped(), transcode.dataDropped(), transcode.elapsedMillis()));
		}

		return converted(file, transcode, commit);
	}

	/**
	 * The date already resolved for the source, which the converted file inherits.
	 */
	private ResolvedMediaDate resolvedDateOf(ConversionSource source) {
		if (source == null || source.captureDate() == null) {
			return null;
		}

		return new ResolvedMediaDate(source.captureDate(), source.dateSource());
	}

	/**
	 * Why this file cannot be converted, as a message key, or empty when it can.
	 * Everything here is a reason to leave the file alone, never to fail the batch.
	 */
	private Optional<String> ineligibilityOf(CatalogFile file, ConversionSource source) {
		if (!file.isActive()) {
			return Optional.of("backend.conversion.skipped.inactive");
		}

		if (file.getFileType() != FileType.VIDEO) {
			return Optional.of("backend.conversion.skipped.notVideo");
		}

		Path path = PathUtils.normalizePath(file.getFileKey());

		if (isHevc(source) && isMp4(file)) {
			return Optional.of("backend.conversion.skipped.alreadyConverted");
		}

		if (!PhysicalFilePolicy.isProcessable(path)) {
			return Optional.of("backend.conversion.skipped.notPhysical");
		}

		if (!Files.exists(path)) {
			return Optional.of("backend.conversion.skipped.missing");
		}

		return Optional.empty();
	}

	private boolean isHevc(ConversionSource source) {
		if (source == null || source.videoCodec() == null) {
			return false;
		}

		return ConversionConstants.HEVC_CODECS.contains(source.videoCodec().trim().toLowerCase(Locale.ROOT));
	}

	/**
	 * The target container. An H.265 video that is not in MP4 yet is still work to
	 * do - a remux - so only the combination of both is already converted.
	 */
	private boolean isMp4(CatalogFile file) {
		return ConversionConstants.OUTPUT_EXTENSION.equals(ExtensionUtils.normalize(file.getExtension()));
	}

	private ConversionFileResult converted(CatalogFile file, TranscodeResult transcode, CommitResult commit) {
		long originalBytes = sizeOf(file);
		long convertedBytes = sizeOf(commit.converted());
		long savedBytes = originalBytes - convertedBytes;

		String message = commit.failure() == null
				? message("backend.conversion.converted", SizeFormatter.format(Math.max(0, savedBytes)))
				: failureMessage(commit.failure());

		return new ConversionFileResult(file.getPublicId(), file.getFileName(), ConversionOutcome.CONVERTED,
				originalBytes, convertedBytes, savedBytes, SizeFormatter.format(Math.max(0, savedBytes)),
				transcode.elapsedMillis(), adjustmentsOf(transcode), commit.originalQuarantined(),
				commit.revivedCatalogEntry(), message);
	}

	private ConversionFileResult failed(CatalogFile file, TranscodeResult transcode) {
		return new ConversionFileResult(file.getPublicId(), file.getFileName(), ConversionOutcome.FAILED, sizeOf(file),
				0, 0, SizeFormatter.format(0), transcode.elapsedMillis(), adjustmentsOf(transcode), false, false,
				failureMessage(transcode.failure()));
	}

	private ConversionAdjustments adjustmentsOf(TranscodeResult transcode) {
		return new ConversionAdjustments(transcode.audioFallback(), transcode.subtitlesDropped(),
				transcode.dataDropped());
	}

	private ConversionFileResult cancelledItem(CatalogFile file) {
		return new ConversionFileResult(file.getPublicId(), file.getFileName(), ConversionOutcome.CANCELLED,
				sizeOf(file), 0, 0, SizeFormatter.format(0), 0, ConversionAdjustments.none(), false, false,
				message("backend.conversion.cancelled"));
	}

	private ConversionFileResult skipped(CatalogFile file, String messageKey) {
		return new ConversionFileResult(file.getPublicId(), file.getFileName(), ConversionOutcome.SKIPPED, sizeOf(file),
				0, 0, SizeFormatter.format(0), 0, ConversionAdjustments.none(), false, false, message(messageKey));
	}

	/**
	 * Every failure the user can see is localized here, so no other class has to
	 * know how to phrase one and the screen only ever receives finished text.
	 */
	private String failureMessage(ConversionFailure failure) {
		return switch (failure) {
		case ENCODER_FAILED -> message("backend.conversion.failed.encoder");
		case OUTPUT_MISSING -> message("backend.conversion.failed.outputMissing");
		case NOT_HEVC -> message("backend.conversion.failed.notHevc");
		case DURATION_MISMATCH -> message("backend.conversion.failed.durationMismatch");
		case NOT_PROBEABLE -> message("backend.conversion.failed.notProbeable");
		case PLACEMENT_FAILED -> message("backend.conversion.failed.placement");
		case CATALOG_FAILED -> message("backend.conversion.failed.catalog");
		case QUARANTINE_FAILED -> message("backend.conversion.failed.quarantine");
		case CANCELLED -> message("backend.conversion.cancelled");
		};
	}

	private Map<UUID, ConversionSource> sourcesById(Collection<UUID> publicIds) {
		Map<UUID, ConversionSource> sources = new HashMap<>();

		for (ConversionSource source : conversionCandidateRepository.findSourcesByPublicIdIn(publicIds)) {
			sources.put(source.publicId(), source);
		}

		return sources;
	}

	private long sizeOf(CatalogFile file) {
		return file.getSizeBytes() == null ? 0 : file.getSizeBytes();
	}

	private long sizeOf(Path file) {
		try {
			return Files.size(file);
		} catch (IOException e) {
			log.warn("Could not read the size of the converted file {}", file, e);

			return 0;
		}
	}

	private int savedPercent(long originalBytes, long savedBytes) {
		if (originalBytes <= 0 || savedBytes <= 0) {
			return 0;
		}

		return Math.clamp(Math.round(savedBytes * 100.0 / originalBytes), 0, 100);
	}

	/**
	 * The same sentence, for the result this call returns in-process. The row keeps
	 * the code, which the request that reads it localises.
	 */
	private String resolve(ExecutionMessage outcome) {
		return message(outcome.code(), outcome.args().toArray());
	}

	private Path[] lockedPaths(List<CatalogFile> files, Path quarantineRoot) {
		Stream<Path> paths = files.stream().map(file -> PathUtils.normalizePath(file.getFileKey()));

		return (quarantineRoot == null ? paths : Stream.concat(Stream.of(quarantineRoot), paths)).distinct()
				.toArray(Path[]::new);
	}
}