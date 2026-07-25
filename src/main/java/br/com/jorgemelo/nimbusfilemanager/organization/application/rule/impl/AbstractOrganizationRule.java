package br.com.jorgemelo.nimbusfilemanager.organization.application.rule.impl;

import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationRuleResult;
import br.com.jorgemelo.nimbusfilemanager.organization.application.rule.OrganizationRule;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationRuleReason;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationRuleType;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.projection.OrganizationCandidate;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;

abstract class AbstractOrganizationRule implements OrganizationRule {

	protected OrganizationRuleResult result(OrganizationRuleType rule, OrganizationRuleReason reason,
			OrganizationCandidate candidate, MediaSubcategory subcategory) {
		FileType fileType = FileType.valueOfNullable(candidate.fileType());

		FileCategory category = candidate.category() == null ? FileType.categoryOf(fileType) : candidate.category();

		return new OrganizationRuleResult(rule, reason, category, subcategory, fileType);
	}

	protected boolean matches(OrganizationRuleReason reason) {
		return reason != null;
	}
}