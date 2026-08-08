package br.com.jorgemelo.nimbusfilemanager.execution.application.dto;

import java.util.UUID;

/**
 * One piece of work in flight, in the shape the global banner draws.
 *
 * <p>
 * Not an {@code Execution}: the row carries thirty columns and a lifecycle, and
 * a banner needs a sentence, a number and somewhere to go. Everything here
 * arrives resolved - the labels are already in the reader's language, and the
 * link is already a path - so the page decides layout and nothing else.
 *
 * @param percentComplete null when this kind of work has no honest denominator.
 * The banner draws a bar when there is one and says "running" when there is
 * not; inventing a number for a reconcile would be inventing progress
 * @param currentItemPercent how far into the step being worked on right now, or
 * null when there is no step to report. The count of finished items and the work
 * inside the unfinished one are two different truths, and the first alone lies
 * by omission: a geodata update that has imported all three administrative
 * levels reads 3 of 3 while it is still writing the supplemental territory
 * files, which is exactly what it looked like from the outside
 * @param cancelRequested somebody asked this to stop and it has not stopped yet,
 * which is a state of its own - the work is still running and the row still says
 * so
 */
public record ExecutionActivity(UUID executionId, String executionType, String typeLabel, String status,
		String statusLabel, String sourcePath, Double percentComplete, Integer currentItemPercent, Integer filesFound,
		Integer totalExpected, boolean cancelRequested, String href) {
}