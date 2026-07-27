package br.com.jorgemelo.nimbusfilemanager.shared.application.dto;

/**
 * A background job worth telling the user about, in the shape the page banner
 * needs. Executions have their own screen and their own record; this exists for
 * the work that has neither - it runs, competes for the machine, and until now
 * the only way to know it was happening was to notice the fans.
 *
 * <p>
 * The label arrives resolved: what the screen receives is text to display, not
 * a domain value to translate.
 */
public record BackgroundJobActivity(String label, String link, long processed, long total, int percent,
		long etaSeconds) {
}