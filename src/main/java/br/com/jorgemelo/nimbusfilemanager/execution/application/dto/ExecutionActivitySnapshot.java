package br.com.jorgemelo.nimbusfilemanager.execution.application.dto;

import java.util.List;

/**
 * Everything in flight right now, as one answer.
 *
 * <p>
 * The banner asks this question rather than asking after an execution it
 * already knows about, and that is the whole point: an id fixed when the page
 * rendered can only ever report on work that had already started. A question
 * with no id in it discovers what began afterwards, and what comes next when
 * this one ends.
 *
 * @param primary the one worth drawing in full, or null when nothing is running
 * @param others the rest, so the page can say there are more without deciding
 * which matters - that order is the queue's own, and it is decided here
 * @param totalActive how many there are in total, including the primary
 */
public record ExecutionActivitySnapshot(ExecutionActivity primary, List<ExecutionActivity> others, int totalActive) {

	/**
	 * Nothing is running - and the page keeps asking, which is how it finds out.
	 */
	public static ExecutionActivitySnapshot idle() {
		return new ExecutionActivitySnapshot(null, List.of(), 0);
	}
}