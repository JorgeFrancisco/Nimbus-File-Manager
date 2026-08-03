package br.com.jorgemelo.nimbusfilemanager.media.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import br.com.jorgemelo.nimbusfilemanager.media.domain.repository.projection.MediaSearchFilter;
import br.com.jorgemelo.nimbusfilemanager.media.domain.repository.projection.MediaSearchRawResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;

public interface MediaSearchRepository extends JpaRepository<CatalogFile, Long> {

	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.media.domain.repository.projection.MediaSearchRawResponse(
				m.publicId,
				m.fileName,
				m.extension,
				CAST(m.fileType AS string),
				m.sizeBytes,
				l.currentPath,
				l.currentFolder,
				m.createdAt,
				m.modifiedAt,
				md.year,
				md.month,
				md.day,
				md.yearMonth,
				v.videoCodec,
				v.audioCodec,
				v.durationSeconds,
				md.displayWidth,
				md.displayHeight,
				md.manufacturer,
				md.model
			)
			FROM CatalogFile m
			LEFT JOIN m.location l
			LEFT JOIN m.metadata md
			LEFT JOIN m.video v
			WHERE (:#{#filter.fileType} IS NULL OR m.fileType = :#{#filter.fileType})
			  AND (:#{#filter.codec} IS NULL OR UPPER(TRIM(v.videoCodec)) = :#{#filter.codec})
			  AND (:#{#filter.folder} IS NULL OR LOWER(l.currentFolder) LIKE LOWER(CONCAT('%', :#{#filter.folder},
					'%')))
			  AND (:#{#filter.extension} IS NULL OR LOWER(m.extension) = LOWER(:#{#filter.extension}))
			  AND (:#{#filter.year} IS NULL OR md.year = :#{#filter.year})
			  AND (:#{#filter.month} IS NULL OR md.month = :#{#filter.month})
			  AND (:#{#filter.scale.minBytes} IS NULL OR m.sizeBytes >= :#{#filter.scale.minBytes})
			  AND (:#{#filter.scale.maxBytes} IS NULL OR m.sizeBytes <= :#{#filter.scale.maxBytes})
			  AND (:#{#filter.scale.minDurationSeconds} IS NULL
			       OR v.durationSeconds >= :#{#filter.scale.minDurationSeconds})
			  AND (:#{#filter.scale.maxDurationSeconds} IS NULL
			       OR v.durationSeconds <= :#{#filter.scale.maxDurationSeconds})
			  AND (:#{#filter.scale.minLongestSide} IS NULL
			       OR GREATEST(COALESCE(md.displayWidth, 0), COALESCE(md.displayHeight, 0))
			          >= :#{#filter.scale.minLongestSide})
			  AND (:#{#filter.camera.manufacturer} IS NULL
			       OR LOWER(md.manufacturer) LIKE LOWER(CONCAT(:#{#filter.camera.manufacturer}, '%')))
			  AND (:#{#filter.camera.model} IS NULL
			       OR LOWER(md.model) LIKE LOWER(CONCAT(:#{#filter.camera.model}, '%')))
			  AND (:#{#filter.requiresLocation} IS NULL
			       OR (CASE WHEN EXISTS (SELECT 1 FROM MediaGeoLocation g WHERE g.id = m.id) THEN TRUE ELSE FALSE END)
			          = :#{#filter.requiresLocation})
			ORDER BY m.modifiedAt DESC, m.id DESC
			""")
	Page<MediaSearchRawResponse> search(MediaSearchFilter filter, Pageable pageable);
}