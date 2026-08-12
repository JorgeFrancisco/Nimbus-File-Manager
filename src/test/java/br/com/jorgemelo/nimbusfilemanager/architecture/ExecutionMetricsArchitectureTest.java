package br.com.jorgemelo.nimbusfilemanager.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * The ratchet under the accumulators: what an execution measures cannot become
 * shared again.
 *
 * <p>
 * The bug these rules exist for did not look like a bug. {@code
 * ProcessingMetrics} and {@code ExecutionPhaseTimings} were ordinary
 * {@code @Component}s, injected the way everything else in the project is
 * injected, and cleared by a {@code reset()} at the start of a run. Every step
 * of that was idiomatic; only the premise was wrong - executions overlap, so one
 * run's numbers were the sum of everybody's, and the clearing threw away a
 * concurrent run's work. Nothing failed. The report just lied.
 *
 * <p>
 * Which is why the guard is structural rather than behavioural: the way back is
 * a single well-meaning annotation, and it would pass every test in the suite.
 * These rules make that annotation a red build.
 */
class ExecutionMetricsArchitectureTest {

	private static final String ROOT = "br.com.jorgemelo.nimbusfilemanager";

	private static final String CONTEXT = ROOT + ".telemetry.application.ExecutionMetricsContext";

	private static final String OWNERSHIP = ROOT + ".execution.application.ExecutionOwnership";

	/** The two accumulators an execution owns, and nobody else may. */
	private static final List<String> ACCUMULATORS = List.of(ROOT + ".telemetry.application.ProcessingMetrics",
			ROOT + ".telemetry.application.ExecutionPhaseTimings");

	/**
	 * What makes Spring hand the same instance to everybody. A container-managed
	 * accumulator is a shared accumulator, whatever the field is called.
	 */
	private static final Set<String> BEAN_ANNOTATIONS = Set.of("org.springframework.stereotype.Component",
			"org.springframework.stereotype.Service", "org.springframework.stereotype.Repository");

	/**
	 * The two genuinely shared collaborators. Their sharing is deliberate - one
	 * pool and one set of permits for the whole application - and it is exactly
	 * that which makes an accumulator field on them fatal: it would be shared too,
	 * silently, by everything that crosses them.
	 */
	private static final List<String> SHARED_COLLABORATORS = List.of(
			ROOT + ".processing.application.ProcessingCoordinator", ROOT + ".processing.application.ExternalToolGate");

	private final JavaClasses production = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT);

	@Test
	void neitherAccumulatorIsASpringBean() {
		for (String name : ACCUMULATORS) {
			JavaClass accumulator = production.get(name);

			for (String annotation : BEAN_ANNOTATIONS) {
				assertThat(accumulator.isAnnotatedWith(annotation) || accumulator.isMetaAnnotatedWith(annotation))
						.as("%s must not be container-managed (%s)", accumulator.getSimpleName(), annotation).isFalse();
			}
		}
	}

	/**
	 * A static field would be one accumulator for the whole JVM - the singleton
	 * back again, this time without Spring's help.
	 */
	@Test
	void noAccumulatorIsReachableThroughAStaticField() {
		List<String> shared = new ArrayList<>();

		for (JavaClass type : production) {
			for (JavaField field : type.getFields()) {
				if (field.getModifiers().contains(JavaModifier.STATIC) && isAccumulatorOrContext(field.getRawType())) {
					shared.add(type.getName() + "." + field.getName());
				}
			}
		}

		assertThat(shared).as("an accumulator reachable without being given it").isEmpty();
	}

	/** Not even inside the context, where the per-execution instances are made. */
	@Test
	void theContextItselfHoldsNothingStatic() {
		JavaClass context = production.get(CONTEXT);

		assertThat(context.getFields()).isNotEmpty().allSatisfy(field -> assertThat(field.getModifiers())
				.as("%s must be per-instance", field.getName()).doesNotContain(JavaModifier.STATIC));
	}

	/**
	 * The shared pool and the shared gate take the accumulator as an argument, so
	 * the caller says who is paying. A field would decide it once, for everybody.
	 */
	@Test
	void theSharedPoolAndGateHoldNoAccumulatorOfTheirOwn() {
		for (String name : SHARED_COLLABORATORS) {
			JavaClass collaborator = production.get(name);

			assertThat(collaborator.getFields())
					.as("%s must be told whose numbers these are", collaborator.getSimpleName())
					.noneMatch(field -> isAccumulatorOrContext(field.getRawType()));
		}
	}

	/**
	 * Clearing only ever made sense while one execution existed at a time. An
	 * accumulator that can be emptied is one a concurrent run can be robbed of.
	 */
	@Test
	void neitherAccumulatorCanBeCleared() {
		for (String name : ACCUMULATORS) {
			JavaClass accumulator = production.get(name);

			assertThat(accumulator.getMethods()).as("%s must have no way to be emptied", accumulator.getSimpleName())
					.noneMatch(method -> method.getName().equals("reset") || method.getName().equals("clear"));
		}
	}

	/**
	 * The signature this slice replaced, and the one that must not come back.
	 *
	 * <p>
	 * Writing telemetry used to take an execution id, which is an identifier and
	 * not an authorisation: an attempt that had already been superseded could
	 * hand over the same number as the attempt that replaced it, and the row had
	 * no way to tell them apart. Taking the ownership instead carries the claim
	 * count, which is what the fence compares. Anyone reintroducing the id-only
	 * form would be removing the fence without touching it.
	 */
	@Test
	void noTelemetryWriteIsAuthorisedByAnExecutionIdAlone() {
		JavaClass telemetry = production.get(ROOT + ".telemetry.application.PerformanceTelemetryService");

		assertThat(telemetry.getMethods()).filteredOn(method -> method.getModifiers().contains(JavaModifier.PUBLIC))
				.as("a public telemetry write must be handed the taking, not just the row's id")
				// Asserted non-empty first: a filter that matched nothing would satisfy
				// every predicate below and report a rule nobody is actually holding to.
				.isNotEmpty()
				.allSatisfy(method -> assertThat(method.getRawParameterTypes())
						.anyMatch(parameter -> parameter.getFullName().equals(OWNERSHIP)));
	}

	private boolean isAccumulatorOrContext(JavaClass type) {
		return ACCUMULATORS.contains(type.getFullName()) || CONTEXT.equals(type.getFullName());
	}
}