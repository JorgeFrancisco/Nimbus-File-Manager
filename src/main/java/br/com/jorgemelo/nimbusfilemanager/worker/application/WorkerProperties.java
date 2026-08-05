package br.com.jorgemelo.nimbusfilemanager.worker.application;

import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the worker paces itself.
 *
 * @param maxConcurrent how many executions may run at once, across all types
 * @param leaseSeconds how long a claim is good for before it counts as
 * abandoned
 * @param renewSeconds how often the renewer says the leases are still held
 * @param pollSeconds how long to wait before asking the queue again when there
 * was nothing to take
 * @param maxClaims how many times one execution may be started before it is
 * treated as poison
 * @param lockBackoffSeconds how long an execution waits when its paths were
 * busy, before becoming available again
 * @param shutdownSeconds how long the worker is given to finish what it is on
 * before it is killed
 * @param initialHeap the worker JVM's starting heap, as a JVM size string
 * @param maxHeap the worker JVM's maximum heap, as a JVM size string
 * @param parentPid the process id of the application that started this worker,
 * absent when nobody did
 */
@ConfigurationProperties(prefix = "nimbus-file-manager.worker")
public record WorkerProperties(Integer maxConcurrent, Integer leaseSeconds, Integer renewSeconds, Integer pollSeconds,
		Integer maxClaims, Integer lockBackoffSeconds, Integer shutdownSeconds, String initialHeap,
		String maxHeap, Long parentPid) {

	public static final int DEFAULT_MAX_CONCURRENT = 3;
	public static final int DEFAULT_LEASE_SECONDS = 120;
	public static final int DEFAULT_RENEW_SECONDS = 30;
	public static final int DEFAULT_POLL_SECONDS = 5;
	public static final int DEFAULT_MAX_CLAIMS = 3;
	public static final int DEFAULT_LOCK_BACKOFF_SECONDS = 10;
	public static final int DEFAULT_SHUTDOWN_SECONDS = 30;

	/**
	 * The worker's heap, which is the point of running it apart.
	 *
	 * <p>
	 * Larger than the application's because this is where the work is: decoding
	 * images, hashing, walking libraries. Configurable rather than literal so the
	 * budget can follow the machine, and applied by the application when it builds
	 * the worker's command line - which is the only moment a heap can be chosen.
	 *
	 * <p>
	 * These bound the Java heap alone. ffmpeg runs as its own process and takes
	 * memory the JVM never sees, and neither number is a limit on what the worker
	 * costs the machine.
	 */
	public static final String DEFAULT_INITIAL_HEAP = "512m";

	public static final String DEFAULT_MAX_HEAP = "4g";

	public int maxConcurrentOrDefault() {
		return maxConcurrent == null ? DEFAULT_MAX_CONCURRENT : maxConcurrent;
	}

	/**
	 * The lease has to outlast several renewal rounds, or one slow round would
	 * hand a running job to another claimer.
	 */
	public int leaseSecondsOrDefault() {
		return leaseSeconds == null ? DEFAULT_LEASE_SECONDS : leaseSeconds;
	}

	public int renewSecondsOrDefault() {
		return renewSeconds == null ? DEFAULT_RENEW_SECONDS : renewSeconds;
	}

	public int pollSecondsOrDefault() {
		return pollSeconds == null ? DEFAULT_POLL_SECONDS : pollSeconds;
	}

	public int maxClaimsOrDefault() {
		return maxClaims == null ? DEFAULT_MAX_CLAIMS : maxClaims;
	}

	public int lockBackoffSecondsOrDefault() {
		return lockBackoffSeconds == null ? DEFAULT_LOCK_BACKOFF_SECONDS : lockBackoffSeconds;
	}

	public String initialHeapOrDefault() {
		return initialHeap == null || initialHeap.isBlank() ? DEFAULT_INITIAL_HEAP : initialHeap;
	}

	public String maxHeapOrDefault() {
		return maxHeap == null || maxHeap.isBlank() ? DEFAULT_MAX_HEAP : maxHeap;
	}

	/**
	 * The grace period on shutdown. Long enough for a batch to finish and write
	 * what it did, short enough that an update is not left waiting on it.
	 */
	public int shutdownSecondsOrDefault() {
		return shutdownSeconds == null ? DEFAULT_SHUTDOWN_SECONDS : shutdownSeconds;
	}

	/**
	 * Whether there is an application to outlive. Absent for a worker somebody
	 * started by hand, which has no supervisor and therefore nothing to watch.
	 */
	public Optional<Long> parentProcess() {
		return Optional.ofNullable(parentPid);
	}
}