package br.com.jorgemelo.nimbusfilemanager.worker.infrastructure;

import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.application.CoverageGenerated;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerProcessExit;
import br.com.jorgemelo.nimbusfilemanager.worker.application.constants.WorkerConstants;

/**
 * Ends the worker JVM: ordered if it can be, forced if it will not.
 *
 * <p>
 * {@code System.exit} runs the shutdown hooks, which is what stops the
 * subprocesses and closes what is open, and is therefore the way to leave
 * whenever leaving is possible. What it cannot promise is finishing: a hook
 * that waits on a database the dying application took with it would hold this
 * process open indefinitely, and an installation folder held open is an update
 * that fails outright.
 *
 * <p>
 * So a watchdog starts first and halts the JVM if the ordered path overruns.
 * {@code halt} skips every hook by design - it is the answer for the case where
 * running them is exactly what is not working.
 *
 * <p>
 * Behind the same property that decides whether this installation runs a worker
 * process at all, and for the same reason. A test JVM hosts worker contexts
 * without being a worker: it has no supervisor, nobody is watching for its exit,
 * and a context that decided to stand down would end the run that was
 * exercising it - which is exactly what happened once. Where the property is
 * off, {@link NoOpWorkerProcessExit} answers instead, and standing down means
 * ceasing to claim rather than ceasing to exist.
 */
@CoverageGenerated("the only thing it does is end this JVM, which a test cannot let happen")
@Component
@Profile(NimbusProfiles.WORKER)
@ConditionalOnProperty(name = WorkerConstants.SUPERVISE_PROPERTY, havingValue = "true", matchIfMissing = true)
public class RuntimeWorkerProcessExit implements WorkerProcessExit {

	/**
	 * Long enough for hooks that are going to finish, short enough that one which
	 * is not does not decide how long this takes.
	 */
	private static final long ORDERLY_TIMEOUT_SECONDS = 10;

	@Override
	public void end(int exitCode) {
		watchdog(exitCode).start();

		System.exit(exitCode);
	}

	private Thread watchdog(int exitCode) {
		Thread thread = new Thread(() -> haltAfterTimeout(exitCode), "nimbus-worker-halt");

		thread.setDaemon(true);

		return thread;
	}

	private void haltAfterTimeout(int exitCode) {
		try {
			TimeUnit.SECONDS.sleep(ORDERLY_TIMEOUT_SECONDS);
		} catch (InterruptedException _) {
			Thread.currentThread().interrupt();

			return;
		}

		Runtime.getRuntime().halt(exitCode);
	}
}