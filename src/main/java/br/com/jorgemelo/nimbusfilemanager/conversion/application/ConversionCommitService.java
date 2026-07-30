package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.CommitResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionOptions;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionFailure;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.ResolvedMediaDate;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantineIntakeService;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.IntakeOutcome;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import lombok.extern.slf4j.Slf4j;

/**
 * Everything that happens once a conversion is proven good, in the only order
 * that is safe:
 *
 * <ol>
 * <li>the converted file is moved into the library under a name that cannot
 * collide with its source;</li>
 * <li>only then, if the user asked for it, the original is soft-deleted into
 * the shared quarantine and the converted file takes its name;</li>
 * <li>finally the new file is cataloged at wherever it ended up.</li>
 * </ol>
 *
 * The original is therefore never touched before the replacement is safely in
 * place, and a failure at step 1 leaves the library exactly as it was. Steps 2
 * and 3 are reported as warnings rather than failures: the user does have the
 * converted file, and pretending otherwise would be worse than saying what did
 * not happen.
 */
@Slf4j
@Service
public class ConversionCommitService {

	private final ConversionFilePlacement conversionFilePlacement;
	private final QuarantineIntakeService quarantineIntakeService;
	private final ConversionCatalogService conversionCatalogService;
	private final ConversionFileNaming conversionFileNaming;

	public ConversionCommitService(ConversionFilePlacement conversionFilePlacement,
			QuarantineIntakeService quarantineIntakeService, ConversionCatalogService conversionCatalogService,
			ConversionFileNaming conversionFileNaming) {
		this.conversionFilePlacement = conversionFilePlacement;
		this.quarantineIntakeService = quarantineIntakeService;
		this.conversionCatalogService = conversionCatalogService;
		this.conversionFileNaming = conversionFileNaming;
	}

	/** The configured quarantine root, or empty when there is none. */
	public Optional<Path> quarantineRoot() {
		return quarantineIntakeService.root();
	}

	/**
	 * @param quarantineRoot where the original goes, or {@code null} to keep it
	 */
	public CommitResult commit(Execution execution, CatalogFile file, Path converted, Path quarantineRoot,
			ConversionOptions options, ResolvedMediaDate originalDate) {
		Path source = Path.of(file.getFileKey());

		FileTime sourceModified = lastModifiedOf(source);

		Path placed;

		try {
			placed = conversionFilePlacement.place(converted, source, options);

			inheritModifiedTime(placed, sourceModified);

			log.info("Converted {} placed as {}", source.getFileName(), placed.getFileName());
		} catch (Exception e) {
			log.error("Could not rename the converted file {} next to {}", converted, source, e);

			conversionFileNaming.discard(converted);

			return CommitResult.failed(ConversionFailure.PLACEMENT_FAILED);
		}

		if (quarantineRoot == null) {
			return catalogQuietly(placed, false, null, originalDate);
		}

		IntakeOutcome outcome = quarantineIntakeService.intake(execution, file, quarantineRoot,
				MovementReason.CONVERTED_QUARANTINED);

		if (outcome != IntakeOutcome.MOVED) {
			log.warn("The converted file for {} is in place, but the original could not be quarantined ({})", source,
					outcome);

			return catalogQuietly(placed, false, ConversionFailure.QUARANTINE_FAILED, originalDate);
		}

		// With an affix configured the converted name is the one the user asked for,
		// so only an unnamed conversion inherits the original's name.
		Path finalPath = conversionFileNaming.affix(options).isEmpty()
				? conversionFilePlacement.renameToOriginalName(placed, source)
				: placed;

		return catalogQuietly(finalPath, true, null, originalDate);
	}

	/**
	 * The catalog write is the last step and the least critical one: the file is
	 * already where the user expects it, and the watcher/reconciliation would pick
	 * it up anyway, so a failure here is reported and never undoes the conversion.
	 */
	private CommitResult catalogQuietly(Path placed, boolean originalQuarantined, ConversionFailure warning,
			ResolvedMediaDate originalDate) {
		try {
			conversionCatalogService.catalog(placed, originalDate);
		} catch (Exception e) {
			log.error("The converted file {} is in the library but could not be cataloged", placed, e);

			return CommitResult.partial(placed, originalQuarantined, ConversionFailure.CATALOG_FAILED);
		}

		return warning == null ? CommitResult.committed(placed, originalQuarantined)
				: CommitResult.partial(placed, originalQuarantined, warning);
	}

	private FileTime lastModifiedOf(Path source) {
		try {
			return Files.getLastModifiedTime(source);
		} catch (IOException e) {
			log.debug("Could not read the modified time of {}", source, e);

			return null;
		}
	}

	/**
	 * The converted file carries the original's modified time. Without it the new
	 * file looks written today, and a video with no embedded or name date would be
	 * dated by the conversion instant - the capture date falls back to the oldest
	 * filesystem timestamp, so restoring this one is what keeps decade-old footage
	 * out of today's timeline. It also keeps the file honest for Explorer, Photos
	 * and any backup tool that sorts by date.
	 */
	private void inheritModifiedTime(Path placed, FileTime sourceModified) {
		if (sourceModified == null) {
			return;
		}

		try {
			Files.setLastModifiedTime(placed, sourceModified);
		} catch (IOException e) {
			log.debug("Could not carry the modified time over to {}", placed, e);
		}
	}
}