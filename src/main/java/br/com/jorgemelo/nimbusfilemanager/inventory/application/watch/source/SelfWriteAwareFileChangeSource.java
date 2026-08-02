package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;

/**
 * Wraps any {@link FileChangeSource} and drops the changes the application
 * itself produced, so writing a file the application already catalogues does
 * not trigger a full inventory of the library. Every other change - and the
 * overflow signal, which is the source's own admission that it may have missed
 * something - passes through untouched.
 *
 * <p>
 * Filtering here rather than in each implementation keeps the rule in one
 * place: the sources already differ (USN journal, {@code WatchService}) and
 * neither should have to know why a change is uninteresting.
 */
class SelfWriteAwareFileChangeSource implements FileChangeSource {

	private final FileChangeSource delegate;
	private final SelfWrittenPathRegistry selfWrittenPathRegistry;
	private final ScanExclusionService scanExclusionService;

	SelfWriteAwareFileChangeSource(FileChangeSource delegate, SelfWrittenPathRegistry selfWrittenPathRegistry,
			ScanExclusionService scanExclusionService) {
		this.delegate = delegate;
		this.selfWrittenPathRegistry = selfWrittenPathRegistry;
		this.scanExclusionService = scanExclusionService;
	}

	@Override
	public List<Path> pollChangedFiles() {
		return delegate.pollChangedFiles().stream().filter(this::worthAnInventory).toList();
	}

	/**
	 * Two kinds of change the application caused itself.
	 *
	 * <p>
	 * A single registered write is consumed once and forgotten. A folder the
	 * application owns is excluded for as long as it is configured: quarantine,
	 * and the catalog backups - which are deliberately put on a synchronised
	 * drive, often inside the watched library. A backup written there looks like
	 * hundreds of MB of new files arriving, and answering it means inventorying
	 * while the file is still being written.
	 */
	private boolean worthAnInventory(Path changed) {
		if (scanExclusionService.isApplicationOwned(changed)) {
			return false;
		}

		return !selfWrittenPathRegistry.consume(changed);
	}

	@Override
	public boolean consumeOverflow() {
		return delegate.consumeOverflow();
	}

	@Override
	public Path root() {
		return delegate.root();
	}

	@Override
	public void close() throws IOException {
		delegate.close();
	}
}