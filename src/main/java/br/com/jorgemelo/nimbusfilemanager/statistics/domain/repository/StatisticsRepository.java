package br.com.jorgemelo.nimbusfilemanager.statistics.domain.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.statistics.domain.repository.projection.CodecStatisticsRawResponse;
import br.com.jorgemelo.nimbusfilemanager.statistics.domain.repository.projection.ExtensionStatisticsRawResponse;
import br.com.jorgemelo.nimbusfilemanager.statistics.domain.repository.projection.FolderStatisticsRawResponse;
import br.com.jorgemelo.nimbusfilemanager.statistics.domain.repository.projection.StatisticsSummaryRawResponse;

public interface StatisticsRepository extends Repository<CatalogFile, Long> {

	/**
	 * Only ACTIVE rows count towards totalFiles/photos/.../ *SizeBytes - a file
	 * that reconcile marked MISSING (source moved or was deleted on disk) or that
	 * was explicitly DELETED shouldn't keep inflating the Dashboard totals forever.
	 * "deleted" itself stays a global count of every DELETED row, since it's meant
	 * to report how many were removed, not how many are currently active.
	 */
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.statistics.domain.repository.projection.StatisticsSummaryRawResponse(

				COALESCE(SUM(CASE WHEN m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE THEN 1 ELSE 0 END), 0),

				COALESCE(SUM(CASE WHEN m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE AND m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.PHOTO THEN 1 ELSE 0 END), 0),
				COALESCE(SUM(CASE WHEN m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE AND m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.VIDEO THEN 1 ELSE 0 END), 0),
				COALESCE(SUM(CASE WHEN m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE AND m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.AUDIO THEN 1 ELSE 0 END), 0),
				COALESCE(SUM(CASE WHEN m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE AND m.fileType IN (
					br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.PDF,
					br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.WORD,
					br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.EXCEL,
					br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.POWERPOINT,
					br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.TEXT
				) THEN 1 ELSE 0 END), 0),
				COALESCE(SUM(CASE WHEN m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE AND m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.OTHER THEN 1 ELSE 0 END), 0),
				COALESCE(SUM(CASE WHEN m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.DELETED THEN 1 ELSE 0 END), 0),

				COALESCE(SUM(CASE WHEN m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE THEN m.sizeBytes ELSE 0 END), 0),
				COALESCE(SUM(CASE WHEN m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE AND m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.PHOTO THEN m.sizeBytes ELSE 0 END), 0),
				COALESCE(SUM(CASE WHEN m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE AND m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.VIDEO THEN m.sizeBytes ELSE 0 END), 0),
				COALESCE(SUM(CASE WHEN m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE AND m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.AUDIO THEN m.sizeBytes ELSE 0 END), 0),
				COALESCE(SUM(CASE WHEN m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE AND m.fileType IN (
					br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.PDF,
					br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.WORD,
					br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.EXCEL,
					br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.POWERPOINT,
					br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.TEXT
				) THEN m.sizeBytes ELSE 0 END), 0),
				COALESCE(SUM(CASE WHEN m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE AND m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.OTHER THEN m.sizeBytes ELSE 0 END), 0)
			)
			FROM CatalogFile m
			""")
	StatisticsSummaryRawResponse summary();

	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.statistics.domain.repository.projection.CodecStatisticsRawResponse(
				UPPER(TRIM(v.videoCodec)),
				SUM(CASE WHEN m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.MISSING
					THEN 0 ELSE 1 END),
				SUM(CASE WHEN m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.MISSING
					THEN 1 ELSE 0 END),
				(SUM(CASE WHEN m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.MISSING
					THEN 0 ELSE 1 END) * 100.0 / (
					SELECT COUNT(v2)
					FROM Video v2
					JOIN v2.catalogFile m2
					WHERE v2.videoCodec IS NOT NULL
					  AND TRIM(v2.videoCodec) <> ''
					  AND m2.lifecycleStatus <> br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.MISSING
				)),
				COALESCE(SUM(CASE WHEN m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.MISSING
					THEN 0 ELSE m.sizeBytes END), 0)
			)
			FROM Video v
			JOIN v.catalogFile m
			WHERE v.videoCodec IS NOT NULL
			  AND TRIM(v.videoCodec) <> ''
			GROUP BY UPPER(TRIM(v.videoCodec))
			ORDER BY 2 DESC, 5 DESC
			""")
	List<CodecStatisticsRawResponse> codecs();

	/**
	 * File counts and total size grouped by extension (mp4, png, jpg...), with each
	 * extension's percentage of the (active) file count. Only counts rows that are
	 * present and not deleted, consistent with {@link #summary()}.
	 */
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.statistics.domain.repository.projection.ExtensionStatisticsRawResponse(
				LOWER(m.extension),
				m.fileType,
				COUNT(m),
				(COUNT(m) * 100.0 / (
					SELECT COUNT(m2)
					FROM CatalogFile m2
					WHERE m2.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
					  AND m2.extension IS NOT NULL AND TRIM(m2.extension) <> ''
				)),
				COALESCE(SUM(m.sizeBytes), 0)
			)
			FROM CatalogFile m
			WHERE m.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  AND m.extension IS NOT NULL AND TRIM(m.extension) <> ''
			GROUP BY LOWER(m.extension), m.fileType
			ORDER BY COUNT(m) DESC, COALESCE(SUM(m.sizeBytes), 0) DESC
			""")
	List<ExtensionStatisticsRawResponse> extensions(Pageable pageable);

	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.statistics.domain.repository.projection.FolderStatisticsRawResponse(
				l.currentFolder,

				COUNT(m),
				COALESCE(SUM(CASE WHEN m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.PHOTO THEN 1 ELSE 0 END), 0),
				COALESCE(SUM(CASE WHEN m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.VIDEO THEN 1 ELSE 0 END), 0),
				COALESCE(SUM(CASE WHEN m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.AUDIO THEN 1 ELSE 0 END), 0),
				COALESCE(SUM(CASE WHEN m.fileType IN (
					br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.PDF,
					br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.WORD,
					br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.EXCEL,
					br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.POWERPOINT,
					br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.TEXT
				) THEN 1 ELSE 0 END), 0),
				COALESCE(SUM(CASE WHEN m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.OTHER THEN 1 ELSE 0 END), 0),

				(COALESCE(SUM(m.sizeBytes), 0) * 100.0 / (
					SELECT COALESCE(SUM(m2.sizeBytes), 0)
					FROM CatalogFile m2
					LEFT JOIN m2.video v2
					WHERE (:fileType IS NULL OR m2.fileType = :fileType)
					  AND (:codec IS NULL OR UPPER(TRIM(v2.videoCodec)) = :codec)
				)),

				COALESCE(SUM(m.sizeBytes), 0)
			)
			FROM CatalogFile m
			JOIN m.location l
			LEFT JOIN m.video v
			WHERE l.currentFolder IS NOT NULL
			  AND TRIM(l.currentFolder) <> ''
			  AND (:fileType IS NULL OR m.fileType = :fileType)
			  AND (:codec IS NULL OR UPPER(TRIM(v.videoCodec)) = :codec)
			GROUP BY l.currentFolder
			ORDER BY COALESCE(SUM(m.sizeBytes), 0) DESC, COUNT(m) DESC
			""")
	List<FolderStatisticsRawResponse> foldersBySize(FileType fileType, String codec, Pageable pageable);

	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.statistics.domain.repository.projection.FolderStatisticsRawResponse(
				l.currentFolder,

				COUNT(m),
				COALESCE(SUM(CASE WHEN m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.PHOTO THEN 1 ELSE 0 END), 0),
				COALESCE(SUM(CASE WHEN m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.VIDEO THEN 1 ELSE 0 END), 0),
				COALESCE(SUM(CASE WHEN m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.AUDIO THEN 1 ELSE 0 END), 0),
				COALESCE(SUM(CASE WHEN m.fileType IN (
					br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.PDF,
					br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.WORD,
					br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.EXCEL,
					br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.POWERPOINT,
					br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.TEXT
				) THEN 1 ELSE 0 END), 0),
				COALESCE(SUM(CASE WHEN m.fileType = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType.OTHER THEN 1 ELSE 0 END), 0),

				(COALESCE(SUM(m.sizeBytes), 0) * 100.0 / (
					SELECT COALESCE(SUM(m2.sizeBytes), 0)
					FROM CatalogFile m2
					LEFT JOIN m2.video v2
					WHERE (:fileType IS NULL OR m2.fileType = :fileType)
					  AND (:codec IS NULL OR UPPER(TRIM(v2.videoCodec)) = :codec)
				)),

				COALESCE(SUM(m.sizeBytes), 0)
			)
			FROM CatalogFile m
			JOIN m.location l
			LEFT JOIN m.video v
			WHERE l.currentFolder IS NOT NULL
			  AND TRIM(l.currentFolder) <> ''
			  AND (:fileType IS NULL OR m.fileType = :fileType)
			  AND (:codec IS NULL OR UPPER(TRIM(v.videoCodec)) = :codec)
			GROUP BY l.currentFolder
			ORDER BY COUNT(m) DESC, COALESCE(SUM(m.sizeBytes), 0) DESC
			""")
	List<FolderStatisticsRawResponse> foldersByFiles(FileType fileType, String codec, Pageable pageable);
}