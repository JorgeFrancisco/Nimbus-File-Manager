package br.com.jorgemelo.nimbusfilemanager.organization.application;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;

/**
 * How an undo says how it ended, given the three counts every ending reports.
 *
 * <p>
 * A finished, a cancelled and an interrupted reversal differ only in which
 * sentence they choose; the numbers are the same three either way. Passing the
 * choice as a function keeps the one place that writes the outcome from having
 * to know which of them it is writing.
 */
@FunctionalInterface
public interface UndoOutcomeMessage {

	ExecutionMessage apply(long undone, long skipped, long errors);
}