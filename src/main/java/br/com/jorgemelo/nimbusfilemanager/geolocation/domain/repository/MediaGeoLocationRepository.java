package br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.MediaGeoLocation;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.projection.MediaCoordinatesProjection;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationConfidence;

public interface MediaGeoLocationRepository extends JpaRepository<MediaGeoLocation, Long> {

	/**
	 * The resolved locations of the given media.
	 *
	 * <p>
	 * An array parameter rather than an {@code IN} list because organizing a
	 * folder plans up to {@code OrganizationPreviewRequest.MAX_LIMIT} files in one
	 * pass and asks this about all of them - more than the wire protocol's 65.535
	 * bind parameters, so naming them one placeholder each failed on exactly the
	 * libraries the limit exists for.
	 */
	@Query("""
			select gl from MediaGeoLocation gl
			where inArray(gl.id, :catalogFileIds)
			""")
	List<MediaGeoLocation> findByIdIn(@Param("catalogFileIds") Long[] catalogFileIds);

	/**
	 * GPS coordinates of the given media, straight from the same entity that owns
	 * the resolved location identity (catalog_file_id).
	 */
	@Query("""
			select m.id as id, m.latitude as latitude, m.longitude as longitude
			from MediaMetadata m
			where m.id in :ids
			""")
	List<MediaCoordinatesProjection> findCoordinatesByIds(@Param("ids") List<Long> ids);

	/**
	 * Media that have GPS but no resolved location yet ("pending").
	 */
	@Query("""
			select m.id
			from MediaMetadata m
			join m.catalogFile mf
			left join MediaGeoLocation gl on gl.id = m.id
			where mf.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  and m.latitude is not null
			  and m.longitude is not null
			  and (m.latitude <> 0 or m.longitude <> 0)
			  and gl.id is null
			  and m.id > :lastId
			order by m.id
			""")
	List<Long> findPendingIds(@Param("lastId") Long lastId, Pageable pageable);

	/**
	 * Media with GPS whose automatic resolution has confidence in the given set
	 * (typically the low ones), for selective rebuild.
	 */
	@Query("""
			select m.id
			from MediaMetadata m
			join m.catalogFile mf
			join MediaGeoLocation gl on gl.id = m.id
			where mf.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  and m.latitude is not null
			  and m.longitude is not null
			  and (m.latitude <> 0 or m.longitude <> 0)
			  and gl.place.confidence in :confidences
			  and m.id > :lastId
			order by m.id
			""")
	List<Long> findIdsByConfidence(@Param("confidences") List<LocationConfidence> confidences,
			@Param("lastId") Long lastId, Pageable pageable);

	/**
	 * All media with GPS, for full rebuild.
	 */
	@Query("""
			select m.id
			from MediaMetadata m
			join m.catalogFile mf
			where mf.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  and m.latitude is not null
			  and m.longitude is not null
			  and (m.latitude <> 0 or m.longitude <> 0)
			  and m.id > :lastId
			order by m.id
			""")
	List<Long> findAllResolvableIds(@Param("lastId") Long lastId, Pageable pageable);

	@Query("""
			select count(m.id)
			from MediaMetadata m
			join m.catalogFile mf
			left join MediaGeoLocation gl on gl.id = m.id
			where mf.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  and m.latitude is not null
			  and m.longitude is not null
			  and (m.latitude <> 0 or m.longitude <> 0)
			  and gl.id is null
			""")
	long countPending();

	@Query("""
			select count(m.id)
			from MediaMetadata m
			join m.catalogFile mf
			join MediaGeoLocation gl on gl.id = m.id
			where mf.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  and m.latitude is not null
			  and m.longitude is not null
			  and (m.latitude <> 0 or m.longitude <> 0)
			  and gl.place.confidence in :confidences
			""")
	long countByConfidenceForRebuild(@Param("confidences") List<LocationConfidence> confidences);

	@Query("""
			select count(m.id)
			from MediaMetadata m
			join m.catalogFile mf
			where mf.lifecycleStatus = br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus.ACTIVE
			  and m.latitude is not null
			  and m.longitude is not null
			  and (m.latitude <> 0 or m.longitude <> 0)
			""")
	long countAllResolvable();
}