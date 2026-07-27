package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;

class FileChangeSourceFactoryTest {

	private final SelfWrittenPathRegistry pathRegistry = new SelfWrittenPathRegistry(Clock.systemDefaultZone());

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
		when(provided.pollChangedFiles()).thenReturn(List.of(changed));

		FileChangeSource source = new FileChangeSourceFactory(_ -> Optional.of(provided), pathRegistry).create(dir,
				true);

		Assertions.assertThat(source.root()).isEqualTo(dir);
		Assertions.assertThat(source.pollChangedFiles()).containsExactly(changed);
	}

	/** What the application wrote itself never reaches the watcher. */
	@Test
	void filtersTheApplicationsOwnWritesOutOfTheProviderSource(@TempDir Path dir) throws IOException {
		Path ours = dir.resolve("converted.mp4");

		FileChangeSource provided = mock(FileChangeSource.class);

		when(provided.pollChangedFiles()).thenReturn(List.of(ours));

		pathRegistry.announce(ours);

		FileChangeSource source = new FileChangeSourceFactory(_ -> Optional.of(provided), pathRegistry).create(dir,
				true);

		Assertions.assertThat(source.pollChangedFiles()).isEmpty();
	}

	@Test
	void fallsBackToTheWatchServiceSourceWhenTheProviderDeclines(@TempDir Path dir) throws IOException {
		FileChangeSourceFactory factory = new FileChangeSourceFactory(_ -> Optional.empty(), pathRegistry);

		FileChangeSource source = factory.create(dir, false);

		try {
			Assertions.assertThat(source.root()).isEqualTo(dir);
		} finally {
			source.close();
		}
	}
}