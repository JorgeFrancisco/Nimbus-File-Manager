package br.com.jorgemelo.nimbusfilemanager.map.domain.repository.projection;

import java.util.UUID;

/**
 * Aggregated EXIF pin row: media grouped by coordinate rounded to 4 decimals.
 */
public interface MapExifPinProjection {

	Double getLat();

	Double getLon();

	long getTotal();

	long getPhotos();

	long getVideos();

	String getCity();

	String getState();

	String getCountry();

	UUID getCoverId();

	String getCoverFileType();

	String getCoverFileName();
}