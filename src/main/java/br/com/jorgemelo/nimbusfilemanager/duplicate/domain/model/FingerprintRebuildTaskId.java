package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model;

import java.io.Serializable;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The composite key of a rebuild task. Exists because JPA needs a class to name
 * the three columns, not because they mean anything apart from the row.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class FingerprintRebuildTaskId implements Serializable {

	private static final long serialVersionUID = 1L;

	private FingerprintKind kind;
	private String algorithm;
	private Long catalogFileId;
}