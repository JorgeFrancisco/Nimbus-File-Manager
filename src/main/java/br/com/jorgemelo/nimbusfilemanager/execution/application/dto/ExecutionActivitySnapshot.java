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
 * @param running how many are actually being worked on, counting the primary
 * @param queued how many are waiting for a worker, counting the primary
 * @param othersLabel what to say about everything except the primary, already
 * written and already translated, or null when the primary is all there is.
 * Running and queued are different facts about an execution and the banner used
 * to add them together and call the total "in progress" - so a queue of five
 * behind one inventory read as six inventories running at once. Which of the
 * two an execution is in is a domain question, and the sentence is composed
 * here for the same reason the labels are
 */
public record ExecutionActivitySnapshot(ExecutionActivity primary, List<ExecutionActivity> others, int running,
		int queued, String othersLabel) {

	/**
	 * Nothing is running - and the page keeps asking, which is how it finds out.
	 */
	public static ExecutionActivitySnapshot idle() {
		return new ExecutionActivitySnapshot(null, List.of(), 0, 0, null);
	}
}