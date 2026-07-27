package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

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

	SelfWriteAwareFileChangeSource(FileChangeSource delegate, SelfWrittenPathRegistry selfWrittenPathRegistry) {
		this.delegate = delegate;
		this.selfWrittenPathRegistry = selfWrittenPathRegistry;
	}

	@Override
	public List<Path> pollChangedFiles() {
		return delegate.pollChangedFiles().stream().filter(changed -> !selfWrittenPathRegistry.consume(changed))
				.toList();
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