package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.MediaQuality;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;

/**
 * Bulk quality lookup by public id (resolution, best date, EXIF presence) for
 * the Duplicados recommendation and its "Resolução" column. Read-only
 * projection, so it never loads the media/photo graph.
 *
 * <p>
 * The ids are one array parameter and not an {@code IN} list because this is
 * not only a page's worth of files: a similarity run asks it about every
 * candidate it analysed, which is the whole eligible library.
 */
public interface MediaQualityRepository extends Repository<CatalogFile, Long> {

	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.MediaQuality(
				m.catalogFilePublicId, md.displayWidth, md.displayHeight, md.captureDate,
				CASE WHEN p.exifJson IS NOT NULL THEN true ELSE false END,
				md.subcategory, md.dateSource,
				CASE WHEN md.manufacturer IS NOT NULL OR md.model IS NOT NULL THEN true ELSE false END)
			FROM CatalogFile m
			LEFT JOIN m.metadata md
			LEFT JOIN m.photo p
			WHERE inArray(m.catalogFilePublicId, :ids)
			""")
	List<MediaQuality> findByCatalogFilePublicIdIn(@Param("ids") UUID[] ids);
}