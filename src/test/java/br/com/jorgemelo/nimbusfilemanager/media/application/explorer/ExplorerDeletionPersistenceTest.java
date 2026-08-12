package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.CatalogFactProvenance;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.CatalogLifecycleWriter;

/**
 * Writing down that the user removed files from the Files screen.
 *
 * <p>
 * A removal is a decision somebody made, not something the catalog noticed, and
 * the fact says so: it is the Explorer's own operation. That is the whole
 * difference between this and a file the walk cannot find - one of them may be
 * met again and the other may not.
 */
class ExplorerDeletionPersistenceTest {

	private static final Instant NOW = Instant.parse("2026-08-14T06:00:00Z");

	private final CatalogLifecycleWriter catalogLifecycleWriter = mock(CatalogLifecycleWriter.class);

	private final ExplorerDeletionPersistence persistence = new ExplorerDeletionPersistence(catalogLifecycleWriter,
			Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void recordsEveryRemovedFileAsSomethingTheUserDid() {
		when(catalogLifecycleWriter.markDeleted(any(), any())).thenReturn(2);

		List<CatalogFile> removed = List.of(CatalogFiles.at(7L, Path.of("D:", "library", "a.jpg")),
				CatalogFiles.at(8L, Path.of("D:", "library", "b.jpg")));

		Assertions.assertThat(persistence.removed(removed)).isEqualTo(2);

		ArgumentCaptor<List<Long>> ids = ArgumentCaptor.captor();
		ArgumentCaptor<CatalogFactProvenance> provenance = ArgumentCaptor.captor();

		verify(catalogLifecycleWriter).markDeleted(ids.capture(), provenance.capture());

		Assertions.assertThat(ids.getValue()).containsExactly(7L, 8L);
		Assertions.assertThat(provenance.getValue().source()).isEqualTo(CatalogEventSources.EXPLORER);
		Assertions.assertThat(provenance.getValue().evidence()).isEqualTo(CatalogEventEvidence.NIMBUS_OPERATION);
		Assertions.assertThat(provenance.getValue().occurredAt()).isEqualTo(NOW);
	}

	/**
	 * A second run over the same folder finds nothing left to remove, which is a
	 * run that did nothing rather than one that failed.
	 */
	@Test
	void aRemovalOfNothingWritesNothing() {
		Assertions.assertThat(persistence.removed(List.of())).isZero();

		verify(catalogLifecycleWriter, never()).markDeleted(any(), any());
	}
}