package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.util.List;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.MetadataRebuildService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildRequest;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MetadataRebuildField;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPreviewRequest;

/**
 * Refreshes the metadata an organization plan will be built from, when the
 * request asked for it.
 *
 * <p>
 * It lives on its own because the same step happens in two handlers - the one
 * that builds a preview and the one that carries out a move - and one place for
 * it is what keeps them from planning off differently-refreshed metadata. Both
 * run in the worker; the application only ever asked for either.
 *
 * <p>
 * This is not the queued rebuild of the settings screen, and deliberately so.
 * It is a step of the organization that asked for it, inside the same execution
 * and bounded by the same request - not work somebody asked for on its own.
 */
@Service
public class OrganizationMetadataRebuild {

	private final MetadataRebuildService metadataRebuildService;

	public OrganizationMetadataRebuild(MetadataRebuildService metadataRebuildService) {
		this.metadataRebuildService = metadataRebuildService;
	}

	/**
	 * Rebuilds nothing unless asked. The date is the default field because it is
	 * the one the layout is built from - a plan is only as right as the dates it
	 * sorted by.
	 */
	public void beforePlanning(OrganizationPreviewRequest request) {
		if (!request.rebuildMetadataValue()) {
			return;
		}

		List<MetadataRebuildField> fields = request.rebuild() == null || request.rebuild().isEmpty()
				? List.of(MetadataRebuildField.DATE)
				: request.rebuild();

		metadataRebuildService
				.rebuild(new MetadataRebuildRequest(request.sourcePath(), fields, null, null, request.limit(), false,
						null));
	}
}