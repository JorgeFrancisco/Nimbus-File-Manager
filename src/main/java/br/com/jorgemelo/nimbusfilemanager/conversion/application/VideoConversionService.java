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
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.CommitResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionFileResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionOptions;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionTotals;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.TranscodeRequest;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.TranscodeResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionFailure;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionOutcome;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.repository.ConversionCandidateRepository;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.repository.projection.ConversionSource;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
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
	private final OperationLockService operationLockService;

	public VideoConversionService(CatalogFileRepository catalogFileRepository,
			ConversionCandidateRepository conversionCandidateRepository, VideoTranscoder videoTranscoder,
			ConversionCommitService conversionCommitService, ConversionExecutionRecorder conversionExecutionRecorder,
			OperationLockService operationLockService) {
		this.catalogFileRepository = catalogFileRepository;
		this.conversionCandidateRepository = conversionCandidateRepository;
		this.videoTranscoder = videoTranscoder;
		this.conversionCommitService = conversionCommitService;
		this.conversionExecutionRecorder = conversionExecutionRecorder;
		this.operationLockService = operationLockService;
	}

	public ConversionResult convert(Collection<UUID> publicIds, ConversionOptions options,
			ConversionProgressCallback progress, BooleanSupplier cancelled) {
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

		progress.update(0, publicIds.size(), 0, null);

		try (var _ = operationLockService.acquire(ExecutionType.CONVERSION, lockedPaths(files, quarantineRoot))) {
			return convertLocked(publicIds, files, effective, quarantineRoot, progress, cancelled);
		} catch (OperationLockException lockError) {
			log.warn("Conversion blocked because another operation is using one of its paths: {}",
					lockError.getMessage());

			return ConversionResult.empty(message("backend.conversion.locked"));
		}
	}

	private ConversionResult convertLocked(Collection<UUID> publicIds, List<CatalogFile> files,
			ConversionOptions options, Path quarantineRoot, ConversionProgressCallback progress,
			BooleanSupplier cancelled) {
		int total = publicIds.size();

		Execution execution = conversionExecutionRecorder.start(folderOf(files), total);

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

			int done = processed;

			progress.update(done, total, 0, file.getFileName());

			ConversionFileResult item = convertOne(execution, file, options, quarantineRoot,
					sources.get(file.getPublicId()),
					percent -> progress.update(done, total, percent, file.getFileName()), cancelled);

			items.add(item);

			switch (item.outcome()) {
			case CONVERTED -> {
				converted++;
				originalBytes += item.originalBytes();
				convertedBytes += item.convertedBytes();
			}
			case SKIPPED -> skipped++;
			case FAILED -> errors++;
			case CANCELLED -> log.info("Conversion cancelled while converting {}", file.getFileName());
			}

			processed++;

			progress.update(processed, total, 100, file.getFileName());
		}

		long savedBytes = originalBytes - convertedBytes;

		ConversionTotals totals = new ConversionTotals(total, converted, skipped, errors, originalBytes, convertedBytes,
				savedBytes);

		boolean stopped = cancelled.getAsBoolean();

		String message = stopped ? message("backend.conversion.cancelledBatch", converted, errors)
				: message("backend.conversion.completed", converted, skipped, errors,
						SizeFormatter.format(Math.max(0, savedBytes)));

		conversionExecutionRecorder.finish(execution, totals, message, stopped);

		return new ConversionResult(true, total, converted, skipped, errors, originalBytes, convertedBytes, savedBytes,
				SizeFormatter.format(Math.max(0, savedBytes)), savedPercent(originalBytes, savedBytes),
				UuidV7.orLegacy(execution.getPublicId(), execution.getId()), message, List.copyOf(items));
	}

	private ConversionFileResult convertOne(Execution execution, CatalogFile file, ConversionOptions options,
			Path quarantineRoot, ConversionSource source, IntConsumer onFilePercent, BooleanSupplier cancelled) {
		Optional<String> ineligible = ineligibilityOf(file, source);

		if (ineligible.isPresent()) {
			return skipped(file, ineligible.get());
		}

		Path path = PathUtils.normalizePath(file.getFileKey());

		Double duration = source == null ? null : source.durationSeconds();

		TranscodeResult transcode = videoTranscoder
				.transcode(new TranscodeRequest(path, duration, options, isHevc(source)), onFilePercent, cancelled);

		if (transcode.failure() == ConversionFailure.CANCELLED) {
			return cancelledItem(file);
		}

		if (!transcode.successful()) {
			return failed(file, transcode);
		}

		CommitResult commit = conversionCommitService.commit(execution, file, transcode.output(), quarantineRoot,
				options);

		if (!commit.successful()) {
			return failed(file, TranscodeResult.failed(commit.failure(), transcode.audioFallback(),
					transcode.subtitlesDropped(), transcode.elapsedMillis()));
		}

		return converted(file, transcode, commit);
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
				transcode.elapsedMillis(), transcode.audioFallback(), transcode.subtitlesDropped(),
				commit.originalQuarantined(), message);
	}

	private ConversionFileResult failed(CatalogFile file, TranscodeResult transcode) {
		return new ConversionFileResult(file.getPublicId(), file.getFileName(), ConversionOutcome.FAILED, sizeOf(file),
				0, 0, SizeFormatter.format(0), transcode.elapsedMillis(), transcode.audioFallback(),
				transcode.subtitlesDropped(), false, failureMessage(transcode.failure()));
	}

	private ConversionFileResult cancelledItem(CatalogFile file) {
		return new ConversionFileResult(file.getPublicId(), file.getFileName(), ConversionOutcome.CANCELLED,
				sizeOf(file), 0, 0, SizeFormatter.format(0), 0, false, false, false,
				message("backend.conversion.cancelled"));
	}

	private ConversionFileResult skipped(CatalogFile file, String messageKey) {
		return new ConversionFileResult(file.getPublicId(), file.getFileName(), ConversionOutcome.SKIPPED, sizeOf(file),
				0, 0, SizeFormatter.format(0), 0, false, false, false, message(messageKey));
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
	 * The folder the conversion ran in, recorded on the execution so the history
	 * shows where it happened. Only informative - each file is locked by its own
	 * path.
	 */
	private Path folderOf(List<CatalogFile> files) {
		return files.stream().findFirst().map(file -> PathUtils.normalizePath(file.getFileKey()).getParent())
				.orElse(null);
	}

	private Path[] lockedPaths(List<CatalogFile> files, Path quarantineRoot) {
		Stream<Path> paths = files.stream().map(file -> PathUtils.normalizePath(file.getFileKey()));

		return (quarantineRoot == null ? paths : Stream.concat(Stream.of(quarantineRoot), paths)).distinct()
				.toArray(Path[]::new);
	}
}