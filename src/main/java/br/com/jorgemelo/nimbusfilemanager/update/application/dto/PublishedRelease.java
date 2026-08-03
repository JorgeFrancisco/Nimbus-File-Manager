package br.com.jorgemelo.nimbusfilemanager.update.application.dto;

/**
 * A release as it was published, with the two files an update needs.
 *
 * @param tag the tag the release hangs under, which is where its version is
 * read from
 * @param page the human page of the release, for somebody who would rather look
 * before installing
 * @param installerName the file name of the installer, kept so a download lands
 * under the name that was published
 * @param installerUrl where the installer is downloaded from
 * @param checksumUrl where the published SHA-256 of that installer is
 * downloaded from
 * @param size the installer's size in bytes, for progress
 */
public record PublishedRelease(String tag, String page, String installerName, String installerUrl, String checksumUrl,
		long size) {
}