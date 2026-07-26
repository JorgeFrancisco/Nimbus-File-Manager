package br.com.jorgemelo.nimbusfilemanager.conversion.domain.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import br.com.jorgemelo.nimbusfilemanager.conversion.domain.repository.projection.ConversionCandidate;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.repository.projection.ConversionSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;

public interface ConversionCandidateRepository extends JpaRepository<CatalogFile, Long> {

	/**
	 * Videos that are still on disk and are not already an H.265 MP4, biggest first
	 * - the order that puts the files with the most to gain in front of the user.
	 * An H.265 video in another container is listed on purpose: it still needs the
	 * MP4 remux, which costs seconds and no quality at all. A video whose codec was
	 * never extracted is kept in the list too, since ffprobe decides at conversion
	 * time and hiding it would silently exclude files from the feature.
	 */
	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.conversion.domain.repository.projection.ConversionCandidate(
				m.publicId,
				m.fileName,
				l.currentPath,
				l.currentFolder,
				m.sizeBytes,
				m.extension,
				v.videoCodec,
				v.durationSeconds,
				md.displayWidth,
				md.displayHeight
			)
			FROM CatalogFile m
			LEFT JOIN m.location l
			LEFT JOIN m.metadata md
			LEFT JOIN m.video v
			WHERE m.fileType = :videoType
			  AND m.lifecycleStatus = :active
			  AND (LOWER(m.extension) <> :outputExtension
			       OR v.videoCodec IS NULL
			       OR LOWER(TRIM(v.videoCodec)) NOT IN :hevcCodecs)
			ORDER BY m.sizeBytes DESC, m.id DESC
			""")
	Page<ConversionCandidate> findCandidates(FileType videoType, LifecycleStatus active, String outputExtension,
			Collection<String> hevcCodecs, Pageable pageable);

	@Query("""
			SELECT new br.com.jorgemelo.nimbusfilemanager.conversion.domain.repository.projection.ConversionSource(
				m.publicId,
				v.videoCodec,
				v.durationSeconds
			)
			FROM CatalogFile m
			LEFT JOIN m.video v
			WHERE m.publicId IN :publicIds
			""")
	List<ConversionSource> findSourcesByPublicIdIn(Collection<UUID> publicIds);
}