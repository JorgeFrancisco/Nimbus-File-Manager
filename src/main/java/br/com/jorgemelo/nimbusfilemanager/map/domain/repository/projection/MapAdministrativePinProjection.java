package br.com.jorgemelo.nimbusfilemanager.map.domain.repository.projection;

import java.util.UUID;

/**
 * Aggregated administrative pin row: coordinate-less media grouped by region.
 */
public interface MapAdministrativePinProjection {

	String getCountryCode();

	String getStateName();

	String getCityName();

	long getTotal();

	long getPhotos();

	long getVideos();

	UUID getCoverId();

	String getCoverFileType();

	String getCoverFileName();
}