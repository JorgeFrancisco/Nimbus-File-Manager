package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.io.File;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.CatalogSignatureProjection;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * A short description of the catalog under a folder, at a moment.
 *
 * <p>
 * The execute recalculates its own plan and deliberately does not read the
 * stored one - so what a user looked at and what a run would do can differ, and
 * that is normal rather than broken: the library moved. What is not acceptable
 * is the user finding out afterwards. Comparing this signature against the one
 * the plan was built with is what lets the screen say "the catalog changed since
 * this plan" instead of staying quiet.
 *
 * <p>
 * Count plus latest update is enough for that. It cannot prove two catalogs are
 * identical - a file added and another removed leave the count alone - but a
 * removal or an addition also touches {@code updatedAt}, so the pair moves for
 * every change this warning is about. It is an indication, and the screen says
 * exactly that; it never blocks a run.
 */
@Component
@Transactional(readOnly = true)
class CatalogSignature {

	private final CatalogFileRepository catalogFileRepository;

	CatalogSignature(CatalogFileRepository catalogFileRepository) {
		this.catalogFileRepository = catalogFileRepository;
	}

	String of(String folder) {
		if (folder == null || folder.isBlank()) {
			return null;
		}

		String normalized = PathUtils.normalize(folder);

		CatalogSignatureProjection signature = catalogFileRepository.signatureUnder(normalized,
				PathUtils.descendantLikePattern(normalized, File.separator));

		if (signature == null) {
			return null;
		}

		LocalDateTime latest = signature.getLatestUpdate();

		return signature.getFileCount() + ":" + (latest == null ? "-" : latest);
	}
}