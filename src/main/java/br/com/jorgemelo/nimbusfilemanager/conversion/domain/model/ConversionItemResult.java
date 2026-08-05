package br.com.jorgemelo.nimbusfilemanager.conversion.domain.model;

import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionOutcome;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One line of the report a conversion batch leaves behind.
 *
 * <p>
 * It exists because the report stopped being something a batch could hand back:
 * the encoding happens in the worker and the screen asks afterwards, from
 * another process. What is kept is exactly what that report renders and nothing
 * more - what is saved, in particular, is the difference between the two sizes
 * rather than a third column that could disagree with them.
 *
 * <p>
 * Deliberately not a {@code Movement}. A movement means a file went from one
 * place to another and is read as such by the history, the undo and the
 * integrity summary; a converted video is a result, not a move, and borrowing
 * that table would have cost it its meaning.
 */
@Entity
@Table(name = "conversion_item_result")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversionItemResult {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "execution_id", nullable = false)
	private Execution execution;

	/**
	 * The catalogued video this line is about, or null when the batch was asked
	 * for an id the catalog no longer knows.
	 */
	@Column(name = "media_public_id")
	private UUID mediaPublicId;

	@Column(name = "file_name", nullable = false, length = 512)
	private String fileName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private ConversionOutcome outcome;

	@Column(name = "original_bytes", nullable = false)
	private Long originalBytes;

	@Column(name = "converted_bytes", nullable = false)
	private Long convertedBytes;

	private String message;

	@Column(name = "audio_fallback", nullable = false)
	private Boolean audioFallback;

	@Column(name = "subtitles_dropped", nullable = false)
	private Boolean subtitlesDropped;

	@Column(name = "data_dropped", nullable = false)
	private Boolean dataDropped;

	@Column(name = "original_quarantined", nullable = false)
	private Boolean originalQuarantined;
}