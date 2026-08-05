package br.com.jorgemelo.nimbusfilemanager.metadata.domain.model;

import java.io.Serializable;
import java.time.LocalDateTime;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One capture date a dry run would change: what the catalog holds today and what
 * the pass would write instead.
 *
 * <p>
 * The two sources are stored as the enum, never as the sentence the screen
 * shows. A row written under one language and read under another would
 * otherwise answer in the language of whoever happened to run it, and the
 * wording lives in the bundles where every other label of the product lives.
 */
@Entity
@Table(name = "metadata_rebuild_preview_item")
@IdClass(MetadataRebuildPreviewItemId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = { "executionId", "ordinal" })
public class MetadataRebuildPreviewItemRecord implements Serializable {

	private static final long serialVersionUID = 1L;

	@Id
	@Column(name = "execution_id", nullable = false)
	private Long executionId;

	@Id
	@Column(name = "ordinal", nullable = false)
	private Integer ordinal;

	@Column(name = "path", nullable = false, length = 1024)
	private String path;

	@Column(name = "current_date_time")
	private LocalDateTime currentDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "current_source", length = 40)
	private DateSource currentSource;

	@Column(name = "new_date_time")
	private LocalDateTime newDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "new_source", length = 40)
	private DateSource newSource;
}