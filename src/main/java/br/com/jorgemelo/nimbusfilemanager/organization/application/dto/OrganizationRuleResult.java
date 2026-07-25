package br.com.jorgemelo.nimbusfilemanager.organization.application.dto;

import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationRuleReason;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationRuleType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;

public record OrganizationRuleResult(OrganizationRuleType rule, OrganizationRuleReason reason, FileCategory category,
		MediaSubcategory subcategory, FileType fileType) {

	public String ruleName() {
		return rule == null ? OrganizationRuleType.DEFAULT.name() : rule.name();
	}
}