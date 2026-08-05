package br.com.jorgemelo.nimbusfilemanager.organization.application.dto;

import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;

/**
 * One page of a published plan, and everything the screen needs to describe it.
 *
 * <p>
 * The summary comes from the plan's own columns rather than from counting the
 * page: a page is fifty rows and the plan may be a hundred thousand, and the
 * cards describe the plan.
 *
 * @param catalogChanged the catalog under the source folder is not the one this
 * plan was built over. It is information, not a refusal: the run recalculates
 * anyway, and this is what keeps that from being a surprise
 */
public record StoredPlanPage(String sourcePath, String targetPath, OrganizationLayout layout,
		OrganizationSummary summary, boolean catalogChanged, List<OrganizationItem> items, int page, int size,
		int totalItems) {
}