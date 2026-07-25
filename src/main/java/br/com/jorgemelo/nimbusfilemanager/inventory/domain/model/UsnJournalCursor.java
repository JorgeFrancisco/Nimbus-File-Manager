package br.com.jorgemelo.nimbusfilemanager.inventory.domain.model;

import java.time.LocalDateTime;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.ClockHolder;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Persisted position in an NTFS volume's USN Change Journal, so the Windows
 * change source can catch up after a restart instead of rescanning the disk.
 * One row per monitored volume, keyed by the monitored root path.
 * {@code journalId} pins the journal instance - a different value on restart
 * means the journal was recreated and this cursor is stale. {@code nextUsn} is
 * the USN the next read resumes from; it is advanced only after a batch is
 * fully processed.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "usn_journal_cursor")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class UsnJournalCursor {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	@Column(name = "volume_key", nullable = false, unique = true, length = 1024)
	private String volumeKey;

	@Column(name = "journal_id")
	private long journalId;

	@Column(name = "next_usn")
	private long nextUsn;

	@Column(name = "updated_at")
	private LocalDateTime updatedAt;

	@PrePersist
	@PreUpdate
	void onSave() {
		updatedAt = LocalDateTime.now(ClockHolder.clock());
	}
}