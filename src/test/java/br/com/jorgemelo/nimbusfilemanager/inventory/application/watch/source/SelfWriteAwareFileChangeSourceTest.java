package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.WatchRecoveryReason;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.InMemorySelfWrittenPaths;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;

/**
 * The filter that keeps the watcher from reacting to the application's own
 * output - a single converted file used to answer with a full recursive
 * inventory of the whole library.
 */
class SelfWriteAwareFileChangeSourceTest {

	private final SelfWrittenPathRegistry pathRegistry = new SelfWrittenPathRegistry(new InMemorySelfWrittenPaths(),
			Clock.systemDefaultZone());
	private final FileChangeSource delegate = mock(FileChangeSource.class);
	private final ScanExclusionService scanExclusionService = mock(ScanExclusionService.class);

	private final FileChangeSource source = new SelfWriteAwareFileChangeSource(delegate, pathRegistry,
			scanExclusionService);

	@Test
	void dropsWhatTheApplicationWroteAndReportsEverythingElse(@TempDir Path folder) {
		Path ours = folder.resolve("converted.mp4");
		Path theirs = folder.resolve("dropped-in.jpg");

		pathRegistry.announce(ours);

		when(delegate.root()).thenReturn(folder);
		when(delegate.pollChangedFiles()).thenReturn(List.of(ours, theirs));

		Assertions.assertThat(source.pollChangedFiles()).containsExactly(theirs);
	}

	/**
	 * The backup folder is deliberately put on a synchronised drive, which is
	 * often inside the watched library. Writing 600 MB there looked like a flood
	 * of new files, and the watcher answered by inventorying while the file was
	 * still being written - repeatedly, for as long as the dump took.
	 */
	@Test
	void dropsChangesInsideTheFoldersTheApplicationOwns(@TempDir Path folder) {
		Path backup = folder.resolve("nimbus-catalog-20260801-193133.zip");
		Path photo = folder.resolve("holiday.jpg");

		when(scanExclusionService.isApplicationOwned(backup)).thenReturn(true);
		when(delegate.root()).thenReturn(folder);
		when(delegate.pollChangedFiles()).thenReturn(List.of(backup, photo));

		Assertions.assertThat(source.pollChangedFiles()).containsExactly(photo);
	}

	/**
	 * A cloud client synchronising the catalog backup stages the upload in hidden
	 * folders at the root of the same drive. The automatic inventory excludes
	 * hidden paths, so every byte moved there was starting a walk of 146k files to
	 * catalogue nothing.
	 */
	@Test
	void dropsChangesUnderHiddenFolders(@TempDir Path drive) throws IOException {
		Path staging = Files.createDirectory(drive.resolve(".tmp.driveupload"));

		Files.setAttribute(staging, "dos:hidden", Boolean.TRUE);

		Path staged = Files.writeString(staging.resolve("nimbus-catalog.zip.part"), "bytes");
		Path photo = Files.writeString(drive.resolve("holiday.jpg"), "bytes");

		when(delegate.root()).thenReturn(drive);
		when(delegate.pollChangedFiles()).thenReturn(List.of(staged, photo));

		Assertions.assertThat(source.pollChangedFiles()).containsExactly(photo);
	}

	/**
	 * The recovery reason is the source admitting it may have missed changes, so
	 * it has to reach the watcher untouched - including which of the three it was,
	 * since this wrapper has no opinion about any of them.
	 */
	@Test
	void letsTheRecoveryReasonThroughUnchanged() {
		when(delegate.consumeRecoveryReason()).thenReturn(Optional.of(WatchRecoveryReason.EVENTS_LOST));

		Assertions.assertThat(source.consumeRecoveryReason()).contains(WatchRecoveryReason.EVENTS_LOST);

		when(delegate.consumeRecoveryReason()).thenReturn(Optional.of(WatchRecoveryReason.JOURNAL_UNREPLAYABLE));

		Assertions.assertThat(source.consumeRecoveryReason()).contains(WatchRecoveryReason.JOURNAL_UNREPLAYABLE);
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