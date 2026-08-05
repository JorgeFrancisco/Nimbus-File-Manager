package br.com.jorgemelo.nimbusfilemanager.shared.application.constants;

/**
 * The roles one jar can be started in.
 *
 * <p>
 * Two of them are the product: {@link #APP} serves the screens, supervises the
 * database and produces work; {@link #WORKER} claims that work and runs it, in
 * a JVM of its own, which is what keeps heavy processing out of the heap that
 * answers requests.
 *
 * <p>
 * The third, {@link #APP_WORKER_COMBINED}, is a Spring profile group - it
 * activates the other two together and adds nothing of its own. It exists so a
 * developer can press Debug once and step from a controller into a job handler
 * in the same debugger. It is <em>not</em> a third execution model: the work
 * still goes to the queue and still comes back through claim, lock and lease.
 * What it does not give is the isolation the whole design is for, since one JVM
 * means one heap and one garbage collector - so production runs the two apart.
 */
public final class NimbusProfiles {

	public static final String APP = "app";

	public static final String WORKER = "worker";

	public static final String APP_WORKER_COMBINED = "app-worker-combined";

	private NimbusProfiles() {
	}
}