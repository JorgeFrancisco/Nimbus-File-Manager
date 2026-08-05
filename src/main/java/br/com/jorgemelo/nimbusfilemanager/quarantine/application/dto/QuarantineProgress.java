package br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto;

/**
 * What the quarantine screen polls while a batch runs, and reads when it ends.
 *
 * <p>
 * One shape for both the restore and the purge because the screen asks the same
 * three questions of either - how much was asked for, how much of it worked,
 * and what to say about the rest - and the counters on the execution row answer
 * them the same way.
 *
 * @param message the outcome, already localized from the code the row carries
 */
public record QuarantineProgress(boolean running, String executionType, int total, int succeeded, int skipped,
		int errors, String message) {
}