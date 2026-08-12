package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import java.nio.file.Path;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.PlacedConversion;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.InventoryPersistenceService;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.InventoryPersistenceAction;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataOptions;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.ResolvedMediaDate;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.facade.MetadataFacade;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.model.MetadataResult;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ProcessingMetrics;
import lombok.extern.slf4j.Slf4j;

/**
 * Brings a converted file into the catalog the moment it lands in the library,
 * instead of waiting for the watcher to notice it. It reuses the very same
 * extraction and persistence the inventory runs, so the new file gets the exact
 * same metadata, dates, hashes and location treatment as any other - there is
 * no second, conversion-specific idea of what a cataloged file looks like.
 */
@Slf4j
@Service
public class ConversionCatalogService {

	private final InventoryPersistenceService inventoryPersistenceService;
	private final MetadataFacade metadataFacade;

	public ConversionCatalogService(InventoryPersistenceService inventoryPersistenceService,
			MetadataFacade metadataFacade) {
		this.inventoryPersistenceService = inventoryPersistenceService;
		this.metadataFacade = metadataFacade;
	}

	/**
	 * @param originalDate the capture date already resolved for the file this one
	 * replaces, or {@code null} when there is none to inherit.
	 * @return whether this brought a catalog entry back from the dead. Converting
	 * to a path the catalog knew and had marked missing - the previous output of
	 * the same conversion, removed from outside the application - makes that entry
	 * active again, which puts it back in the set a duplicate analysis looks at.
	 * Nothing is announced here: the fact travels to the batch, which says it once
	 */
	public boolean catalog(PlacedConversion placed, ResolvedMediaDate originalDate, ProcessingMetrics metrics) {
		Path file = placed.path();

		// forceAnalysis, so a stale row left at this exact path (a file converted,
		// removed and converted again) is updated instead of quietly kept as a cache
		// hit with the old size, codec and hashes.
		//
		// Hashing is off, and that is not the setting speaking: the move that put this
		// file here read all of it to prove what it holds, so asking the extractor for
		// a digest would be reading a several-gigabyte encode a third time to learn
		// what is already known.
		MetadataOptions options = new MetadataOptions(false, true);

		MetadataResult metadata = keepingTheBestDate(metadataFacade.extract(file, options, metrics), originalDate);

		return inventoryPersistenceService.save(file, withProvenDigest(metadata, placed), options)
				.action() == InventoryPersistenceAction.REACTIVATED;
	}

	/**
	 * The digest the placement proved, on the row being written.
	 *
	 * <p>
	 * It is recorded whatever the library-wide hashing setting says, because that
	 * setting decides whether a <em>scan</em> should pay to read every file it
	 * meets - and nothing is being paid here. A row born without a digest is a row
	 * no duplicate analysis can group, no content verification can settle and no
	 * recovery can recognise if the file is later moved from outside; leaving it
	 * empty while holding the answer would be a gap nobody could explain.
	 */
	private static MetadataResult withProvenDigest(MetadataResult metadata, PlacedConversion placed) {
		if (placed.proven() == null || placed.proven().sha256() == null) {
			return metadata;
		}

		return metadata.toBuilder().sha256(placed.proven().sha256()).build();
	}

	/**
	 * A converted file is written now, so its filesystem timestamps say "now". When
	 * the source had no embedded or name date either, the re-extraction can only
	 * answer with that - which would date decade-old footage as today and push it
	 * to the top of the timeline. The date the original had is more trustworthy
	 * than the conversion instant, so it is what the new row keeps.
	 */
	private MetadataResult keepingTheBestDate(MetadataResult metadata, ResolvedMediaDate originalDate) {
		if (originalDate == null || originalDate.captureDate() == null) {
			return metadata;
		}

		if (DateSource.trustOf(originalDate.dateSource(), originalDate.captureDate()) <= DateSource
				.trustOf(metadata.getDateSource(), metadata.getCaptureDate())) {
			return metadata;
		}

		log.info(
				"Converted file {} keeps the date of the file it replaces ({} from {}) instead of the {} it was"
						+ " written at",
				metadata.getFileName(), originalDate.captureDate(), originalDate.dateSource(),
				metadata.getDateSource());

		return metadata.toBuilder().captureDate(originalDate.captureDate()).dateSource(originalDate.dateSource())
				.build();
	}
}