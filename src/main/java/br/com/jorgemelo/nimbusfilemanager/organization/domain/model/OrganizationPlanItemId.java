package br.com.jorgemelo.nimbusfilemanager.organization.domain.model;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The composite key of a plan item. Exists because JPA needs a class to name the
 * pair, not because the pair means anything on its own.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class OrganizationPlanItemId implements Serializable {

	private static final long serialVersionUID = 1L;

	private Long executionId;
	private Integer ordinal;
}