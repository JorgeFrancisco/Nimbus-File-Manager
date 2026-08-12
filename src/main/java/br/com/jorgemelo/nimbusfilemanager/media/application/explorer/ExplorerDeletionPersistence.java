package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.CatalogFactProvenance;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.CatalogLifecycleWriter;

/**
 * What the catalog is told when the user removes files for good.
 *
 * <p>
 * It used to be told by assignment: the status was set on each row and the rows
 * were saved. That left the removal as a column and nothing else - no when, no
 * at whose request - which is precisely the question someone asks months later
 * about a folder that is no longer there. The door writes the transition and one
 * fact per file together, so the two cannot disagree and neither can arrive
 * without the other.
 *
 * <p>
 * No movement is prepared for this. A movement is a file going from one place to
 * another and says whether it arrived; a permanent delete has no other place.
 * The quarantine route, which does move the file, keeps its movement.
 */
@Component
public class ExplorerDeletionPersistence {

	private final CatalogLifecycleWriter catalogLifecycleWriter;
	private final Clock clock;

	public ExplorerDeletionPersistence(CatalogLifecycleWriter catalogLifecycleWriter, Clock clock) {
		this.catalogLifecycleWriter = catalogLifecycleWriter;
		this.clock = clock;
	}

	/**
	 * @return how many files the catalog actually changed its mind about, which a
	 * second run over the same folder reports as none
	 */
	public int removed(List<CatalogFile> files) {
		if (files.isEmpty()) {
			return 0;
		}

		return catalogLifecycleWriter.markDeleted(files.stream().map(CatalogFile::getId).toList(),
				new CatalogFactProvenance(Instant.now(clock), CatalogEventSources.EXPLORER,
						CatalogEventEvidence.NIMBUS_OPERATION, null));
	}
}