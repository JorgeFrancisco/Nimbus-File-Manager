package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.PersistedCursor;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.model.UsnJournalCursor;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.repository.UsnJournalCursorRepository;

/**
 * Load/save side of the USN journal cursor, keyed by the monitored volume.
 * Keeps the JPA details out of the change source (which is Windows-only and
 * native-heavy) so the persistence is plain, cross-platform and
 * integration-tested on any OS.
 */
@Service
public class UsnCursorStore {

	private final UsnJournalCursorRepository repository;

	public UsnCursorStore(UsnJournalCursorRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	public Optional<PersistedCursor> load(String volumeKey) {
		return repository.findByVolumeKey(volumeKey)
				.map(cursor -> new PersistedCursor(cursor.getJournalId(), cursor.getNextUsn()));
	}

	@Transactional
	public void save(String volumeKey, long journalId, long nextUsn) {
		UsnJournalCursor cursor = repository.findByVolumeKey(volumeKey)
				.orElseGet(() -> UsnJournalCursor.builder().volumeKey(volumeKey).build());

		cursor.setJournalId(journalId);
		cursor.setNextUsn(nextUsn);

		repository.save(cursor);
	}
}