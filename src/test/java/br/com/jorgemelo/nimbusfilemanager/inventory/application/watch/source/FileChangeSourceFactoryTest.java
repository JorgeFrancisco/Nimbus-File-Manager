package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.FileSystemChange;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.Changes;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SelfWrittenPath;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.SelfWriteRole;

class FileChangeSourceFactoryTest {

	/**
	 * Asked, and answering that none of these were ours: the wiring under test is
	 * that the filter is there at all.
	 */
	private final SelfWrittenPathRegistry pathRegistry = mock(SelfWrittenPathRegistry.class);

	/**
	 * Whichever source the platform offers, it reaches the watcher wrapped so the
	 * application's own writes can be filtered out. Asserted through behaviour
	 * rather than the wrapper's type, which is an implementation detail.
	 */
	@Test
	void usesTheProviderSourceWhenOneIsAvailable(@TempDir Path dir) throws IOException {
		Path changed = dir.resolve("dropped-in.jpg");

		FileChangeSource provided = mock(FileChangeSource.class);

		when(provided.root()).thenReturn(dir);
		when(provided.pollChanges()).thenReturn(List.of(Changes.created(changed)));

		FileChangeSource source = new FileChangeSourceFactory(_ -> Optional.of(provided), pathRegistry,
				mock(ScanExclusionService.class)).create(dir, true);

		Assertions.assertThat(source.root()).isEqualTo(dir);
		Assertions.assertThat(source.pollChanges()).extracting(FileSystemChange::path).containsExactly(changed);
	}

	/**
	 * What the application wrote itself never reaches the watcher.
	 *
	 * <p>
	 * The claim is asked for with its role, and the answer is what decides: a file
	 * arriving at a path this product announced it was filling is this product's
	 * own doing. The registry is a real one in the tests that are about the
	 * registry - here it stands in, because what is being checked is that the
	 * factory wires the filter in at all.
	 */
	@Test
	void filtersTheApplicationsOwnWritesOutOfTheProviderSource(@TempDir Path dir) throws IOException {
		Path ours = dir.resolve("converted.mp4");

		FileChangeSource provided = mock(FileChangeSource.class);

		SelfWrittenPathRegistry announcing = mock(SelfWrittenPathRegistry.class);

		when(provided.pollChanges()).thenReturn(List.of(Changes.created(ours)));
		when(announcing.announcedAmong(any()))
				.thenReturn(Set.of(new SelfWrittenPath(ours, SelfWriteRole.OCCUPYING)));

		FileChangeSource source = new FileChangeSourceFactory(_ -> Optional.of(provided), announcing,
				mock(ScanExclusionService.class)).create(dir, true);

		Assertions.assertThat(source.pollChanges()).isEmpty();
	}

	@Test
	void fallsBackToTheWatchServiceSourceWhenTheProviderDeclines(@TempDir Path dir) throws IOException {
		FileChangeSourceFactory factory = new FileChangeSourceFactory(_ -> Optional.empty(), pathRegistry,
				mock(ScanExclusionService.class));

		FileChangeSource source = factory.create(dir, false);

		try {
			Assertions.assertThat(source.root()).isEqualTo(dir);
		} finally {
			source.close();
		}
	}
}