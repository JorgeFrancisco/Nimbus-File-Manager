package br.com.jorgemelo.nimbusfilemanager.update.application.dto;

/**
 * Published the first time a check finds a version worth offering.
 *
 * <p>
 * An event because the tray lives in {@code infrastructure} and the check lives
 * in {@code application}, which does not know it exists - and because the tray
 * is not the only thing that would want to know.
 *
 * <p>
 * Raised once per version, not once per check. A quarter-hourly notification
 * saying the same thing is not a reminder, it is something to be turned off.
 *
 * @param published the version that was found
 */
public record UpdateFound(String published) {
}