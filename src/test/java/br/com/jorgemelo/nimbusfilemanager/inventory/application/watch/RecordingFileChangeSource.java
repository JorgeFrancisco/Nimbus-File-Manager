package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.FileChangeSource;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.WatchRecoveryReason;

/**
 * A source whose two channels the test fills separately, modelled on the
 * Windows one: a backlog that the journal replay produced when the source was
 * built, and live notifications that arrive afterwards.
 *
 * <p>
 * The two behave exactly as they do in production, and the difference between
 * them is the whole point of the class. The backlog is handed to whoever asks
 * first - {@code takeOfflineBacklog()} or the next poll - and is gone either
 * way, so a test cannot accidentally have it counted twice. A live change is
 * only ever reported by the poll, which is what makes it impossible for a full
 * scan to claim it.
 */
class RecordingFileChangeSource implements FileChangeSource {

	private final Path root;
	private final List<Path> live = new ArrayList<>();

	private List<Path> backlog;
	private WatchRecoveryReason reason;

	RecordingFileChangeSource(Path root, List<Path> backlog, WatchRecoveryReason reason) {
		this.root = root;
		this.backlog = List.copyOf(backlog);
		this.reason = reason;
	}

	/** A change seen after the source was built, the way the watcher would. */
	void reportLive(Path changed) {
		live.add(changed);
	}

	@Override
	public List<Path> pollChangedFiles() {
		List<Path> changed = new ArrayList<>(backlog);

		backlog = List.of();

		changed.addAll(live);

		live.clear();

		return changed;
	}

	@Override
	public List<Path> takeOfflineBacklog() {
		List<Path> taken = backlog;

		backlog = List.of();

		return taken;
	}

	@Override
	public Optional<WatchRecoveryReason> consumeRecoveryReason() {
		WatchRecoveryReason current = reason;

		reason = null;

		return Optional.ofNullable(current);
	}

	@Override
	public Path root() {
		return root;
	}

	@Override
	public void close() {
		// Nothing is held: the test owns the lists.
	}
}