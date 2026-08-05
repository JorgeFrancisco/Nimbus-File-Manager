package br.com.jorgemelo.nimbusfilemanager.shared.application;

import org.slf4j.MDC;

/**
 * Which of the two roles produced a log line.
 *
 * <p>
 * Almost always the file name already says it: App and Worker are separate
 * processes writing separate files. This exists for the one case where that is
 * not true - {@code app-worker-combined}, one JVM holding both roles and writing
 * one console - and it is deliberately the smallest thing that answers it.
 *
 * <p>
 * The default is the process's own role, set in {@code logback-spring.xml} from
 * the active profiles, so an installed App or Worker is labelled correctly
 * without anything here running at all. What this adds is the exception: the
 * threads the worker creates for itself say so, and everything else in a
 * combined JVM - a request, the folder watcher, a scheduler, startup - keeps the
 * default, which in that JVM is the application.
 *
 * <p>
 * Deliberately <em>not</em> a general context-propagation mechanism. The
 * marker is set where a thread is created for one role and lives its whole life
 * in it, and carried across the one fan-out that would otherwise lose it. A
 * framework that followed every callback would be a large amount of machinery
 * for a prefix.
 */
public final class LoggingRole {

	/**
	 * Read by {@code logback-spring.xml} as {@code %X{nimbusRole:-…}}. Changing it
	 * here without changing it there silently returns every line to the default.
	 */
	private static final String KEY = "nimbusRole";

	private static final String WORKER = "WORKER";

	private LoggingRole() {
	}

	/**
	 * Marks the calling thread as the worker's, for as long as that thread lives.
	 *
	 * <p>
	 * Called where the worker creates a thread of its own and nothing else will
	 * ever run on it - a claim loop, the lease renewer. There is no matching
	 * clear because there is nothing to clear it for: the thread ends with the
	 * worker.
	 */
	public static void markThisThreadAsWorker() {
		MDC.put(KEY, WORKER);
	}

	/**
	 * The role of the calling thread, to be handed to a thread it is about to give
	 * work to. Null when the caller carries no marker, which is the ordinary case
	 * and means "whatever this process is".
	 */
	public static String current() {
		return MDC.get(KEY);
	}

	/**
	 * Runs a task under a role captured elsewhere, and gives the thread back the
	 * way it found it.
	 *
	 * <p>
	 * For pools, where the thread outlives the task and is reused by whoever comes
	 * next: without the clear in {@code finally}, one execution's marker would
	 * follow the thread into work that has nothing to do with it - including work
	 * of the other role.
	 */
	public static void runAs(String role, Runnable task) {
		if (role == null) {
			task.run();

			return;
		}

		MDC.put(KEY, role);

		try {
			task.run();
		} finally {
			MDC.remove(KEY);
		}
	}
}