package br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Resolved location of a media - a global metadata that shares the identity of
 * {@link MediaMetadata} (catalog_file_id), the same entity that already carries
 * the raw latitude/longitude. Reusable by timeline, organization, details and
 * any future feature; never provider-specific.
 *
 * <p>
 * The resolved place itself (country/state/city + provenance) lives in the
 * shared {@link ResolvedPlace} embeddable, kept in lock-step with
 * {@link GeoResolutionCache}.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "media_geo_location")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class MediaGeoLocation {

	/**
	 * Same identity as {@link MediaMetadata} (catalog_file_id) - mapped as a plain
	 * id (the FK lives in the database) so persistence never needs to load the
	 * metadata aggregate.
	 */
	@Id
	@Column(name = "catalog_file_id")
	@EqualsAndHashCode.Include
	private Long id;

	@Embedded
	private ResolvedPlace place;
}