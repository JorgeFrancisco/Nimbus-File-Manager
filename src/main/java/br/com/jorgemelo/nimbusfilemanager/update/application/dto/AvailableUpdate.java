package br.com.jorgemelo.nimbusfilemanager.update.application.dto;

/**
 * A release worth offering to this installation, with both versions carried so
 * the screen and the tray can say what is being replaced by what.
 *
 * @param installed the version this run is
 * @param published the version being offered
 * @param release where its files are
 */
public record AvailableUpdate(String installed, String published, PublishedRelease release) {
}