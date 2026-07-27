package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;

/**
 * The filter that keeps the watcher from reacting to the application's own
 * output - a single converted file used to answer with a full recursive
 * inventory of the whole library.
 */
class SelfWriteAwareFileChangeSourceTest {

	private final SelfWrittenPathRegistry pathRegistry = new SelfWrittenPathRegistry(Clock.systemDefaultZone());
	private final FileChangeSource delegate = mock(FileChangeSource.class);
	private final FileChangeSource source = new SelfWriteAwareFileChangeSource(delegate, pathRegistry);

	@Test
	void dropsWhatTheApplicationWroteAndReportsEverythingElse(@TempDir Path folder) {
		Path ours = folder.resolve("converted.mp4");
		Path theirs = folder.resolve("dropped-in.jpg");

		pathRegistry.announce(ours);

		when(delegate.pollChangedFiles()).thenReturn(List.of(ours, theirs));

		Assertions.assertThat(source.pollChangedFiles()).containsExactly(theirs);
	}

	/**
	 * Overflow is the source admitting it may have missed changes, so it has to
	 * reach the watcher untouched: that is the signal that forces a full re-scan.
	 */
	@Test
	void letsTheOverflowSignalThrough() {
		when(delegate.consumeOverflow()).thenReturn(true);

		Assertions.assertThat(source.consumeOverflow()).isTrue();
	}

	@Test
	void closesTheSourceItWraps() throws Exception {
		source.close();

		verify(delegate).close();
	}

	/**
	 * The watcher asks the source which tree it is watching; wrapping it must not
	 * change the answer.
	 */
	@Test
	void reportsTheRootOfTheSourceItWraps(@TempDir Path folder) {
		when(delegate.root()).thenReturn(folder);

		Assertions.assertThat(source.root()).isEqualTo(folder);
	}
}