package br.com.jorgemelo.nimbusfilemanager.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * The other boundary the worker phases drew: not who may change a file, but who
 * may spend an hour.
 *
 * <p>
 * A workload that reads every photo in a library, or downloads two gigabytes of
 * boundaries, belongs to the worker - and the way it stops belonging there is
 * never a decision, it is a convenience: a screen that calls the service
 * directly because the answer is wanted now, or a timer that runs the pass
 * itself because enqueueing felt like ceremony. These rules make both a failing
 * build.
 *
 * <p>
 * Which runners stay in the application, and why each one does, is in
 * {@code docs/adr/0008-operacoes-assincronas-da-app.md}.
 */
class HeavyWorkloadArchitectureTest {

	private static final String ROOT = "br.com.jorgemelo.nimbusfilemanager";

	/**
	 * The three that stay in the application, by decision rather than by omission:
	 * one replaces the application itself, one installs the tool the worker needs,
	 * and one dumps and restores the cluster the application supervises. §19 says
	 * why each.
	 */
	private static final Set<String> APPLICATION_RUNNERS = Set.of(ROOT + ".update.application.UpdateInstallAsyncRunner",
			ROOT + ".settings.application.ExternalToolInstallAsyncRunner",
			ROOT + ".backup.application.CatalogBackupAsyncRunner");

	/**
	 * What a worker exists to do. Each one used to be reachable from a screen or a
	 * timer, and each one now is not.
	 */
	private static final List<String> HEAVY_SERVICES = List.of(
			ROOT + ".geolocation.application.LocationRebuildService",
			ROOT + ".metadata.application.MetadataRebuildService",
			ROOT + ".duplicate.application.fingerprint.FingerprintBacklogEngine",
			ROOT + ".duplicate.application.SimilarityAnalyzer");

	private final JavaClasses production = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT);

	/**
	 * Every {@code @Async} left in the codebase belongs to one of the three the
	 * phase decided to keep. A new one is either a workload that skipped the queue
	 * or a decision that was never written down.
	 */
	@Test
	void theOnlyAsyncRunnersLeftAreTheOnesThatStayByDecision() {
		Set<String> runners = production.stream().filter(this::hasAsyncMethod).map(JavaClass::getName)
				.collect(Collectors.toSet());

		assertThat(runners).as("An @Async method outside the three runners that stay in the application by decision."
				+ " Either a workload skipped the queue, or §19 needs to say why this one stays.")
				.isSubsetOf(APPLICATION_RUNNERS);
	}

	/**
	 * No screen calls the work. A controller or a view model that depends on one of
	 * these is a request holding an hour of processing open - which is what queuing
	 * them removed.
	 */
	@Test
	void noScreenReachesAHeavyService() {
		Set<String> offenders = production.stream()
				.filter(javaClass -> javaClass.getPackageName().contains(".infrastructure.web")
						|| javaClass.getPackageName().contains(".infrastructure.rest"))
				.filter(this::dependsOnAHeavyService).map(JavaClass::getName).collect(Collectors.toSet());

		assertThat(offenders).as("A controller or view model reaches a heavy service directly."
				+ " It should ask the queue and read the row instead.").isEmpty();
	}

	/**
	 * No timer runs the work either. A scheduler asks; the worker does.
	 */
	@Test
	void noSchedulerReachesAHeavyService() {
		Set<String> offenders = production.stream()
				.filter(javaClass -> javaClass.getSimpleName().endsWith("Scheduler"))
				.filter(this::dependsOnAHeavyService).map(JavaClass::getName).collect(Collectors.toSet());

		assertThat(offenders)
				.as("A scheduler reaches a heavy service directly. A timer enqueues; the worker carries out.")
				.isEmpty();
	}

	private boolean hasAsyncMethod(JavaClass javaClass) {
		return javaClass.getMethods().stream().anyMatch(method -> method.getAnnotations().stream()
				.anyMatch(annotation -> annotation.getRawType().getSimpleName().equals("Async")));
	}

	/**
	 * The dataset is asked about from several screens - is it installed, how big,
	 * remove it - and only one of its methods is the hour-long one. So this one is
	 * a call, not a dependency: naming the type would forbid asking the question
	 * along with doing the work.
	 */
	private boolean callsTheDatasetAcquisition(JavaClass javaClass) {
		return javaClass.getMethodCallsFromSelf().stream()
				.anyMatch(call -> call.getTargetOwner().getName()
						.equals(ROOT + ".geolocation.application.OfflineGeoDataset")
						&& call.getName().equals("bringUpToDate"));
	}

	private boolean dependsOnAHeavyService(JavaClass javaClass) {
		return callsTheDatasetAcquisition(javaClass) || javaClass.getDirectDependenciesFromSelf().stream()
				.map(dependency -> dependency.getTargetClass().getName()).anyMatch(HEAVY_SERVICES::contains);
	}
}