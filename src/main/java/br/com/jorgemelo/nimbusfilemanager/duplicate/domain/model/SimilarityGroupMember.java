package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model;

import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Reason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Verdict;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One file inside a published group, with the verdict the analysis reached for
 * it.
 *
 * <p>
 * Referenced by public id and <b>not</b> by a foreign key to
 * {@code catalog_file}. A cascade there would delete the group when one of its
 * files is deleted or quarantined - which is precisely the moment the user is
 * acting on this screen - and the rule of the product is the opposite: a
 * published analysis is not undone by the world moving. What to do about a
 * member that is no longer usable is a decision of the reading, taken where the
 * consequences are visible, not a delete taken by the database.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "similarity_group_member")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SimilarityGroupMember {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	@Column(name = "group_id", nullable = false)
	private Long groupId;

	@Column(name = "media_public_id", nullable = false)
	private UUID mediaPublicId;

	@Enumerated(EnumType.STRING)
	@Column(name = "verdict", nullable = false, length = 20)
	private Verdict verdict;

	@Enumerated(EnumType.STRING)
	@Column(name = "reason", length = 40)
	private Reason reason;

	@Column(name = "position", nullable = false)
	private Integer position;
}