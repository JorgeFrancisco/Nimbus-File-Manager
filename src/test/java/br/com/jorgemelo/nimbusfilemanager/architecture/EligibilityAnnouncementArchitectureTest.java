package br.com.jorgemelo.nimbusfilemanager.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.EligibilityChanged;

/**
 * The shape that keeps "who may be analysed changed" a fact said once, by the
 * operation that changed it.
 *
 * <p>
 * The event is easy to use wrongly in exactly two ways, and both were live
 * possibilities while it was being wired. One is publishing per file - from the
 * entity, the mapper, or the persistence adapter a loop calls once per item -
 * which turns a batch of ten thousand into ten thousand requests for the same
 * piece of work. The other is a second consumer, which would make "did anything
 * ask for a regroup?" a question with more than one answer. Neither is
 * detectable by reading a diff; both are detectable here.
 */
class EligibilityAnnouncementArchitectureTest {

	private static final String ROOT = "br.com.jorgemelo.nimbusfilemanager";

	/**
	 * The two that may build the event. Everything else says it through
	 * {@code EligibilityAnnouncer}; the exclusion service publishes directly
	 * because the announcer holds it, and the listener names the type because
	 * consuming it is what it exists for.
	 */
	private static final Set<String> ALLOWED_TO_NAME_THE_EVENT = Set.of(
			ROOT + ".duplicate.application.EligibilityAnnouncer",
			ROOT + ".duplicate.application.DuplicateExclusionService",
			ROOT + ".duplicate.application.SimilarityEligibilityRefresh");

	/**
	 * Endings that mean "called once per item". A class named like this holding
	 * the announcer is the per-file publish this event exists to avoid, whatever
	 * the body says today.
	 */
	private static final Set<String> PER_ITEM_SUFFIXES = Set.of("Persistence", "Mapper", "Repository", "Entity",
			"Writer");

	private final JavaClasses production = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT);

	@Test
	void onlyTheAnnouncerAndTheExclusionServiceBuildTheEvent() {
		Set<String> naming = production.stream()
				.filter(javaClass -> javaClass.getDirectDependenciesFromSelf().stream()
						.anyMatch(dependency -> dependency.getTargetClass().getName()
								.equals(EligibilityChanged.class.getName())))
				.map(JavaClass::getName).filter(name -> !name.contains("$")).collect(Collectors.toSet());

		assertThat(naming).as("An operation says that eligibility changed through EligibilityAnnouncer, which is what"
				+ " keeps the condition and the wording in one place. A class building the event itself is either a"
				+ " second announcer or a publish from somewhere that runs per file.")
				.isSubsetOf(ALLOWED_TO_NAME_THE_EVENT);
	}

	/**
	 * One listener, and it is the one that owns the decision. A second would mean
	 * two things reacting to the same fact, and the failure mode is not a
	 * duplicated regroup - it is nobody being able to say which of them was
	 * supposed to run.
	 */
	@Test
	void exactlyOneClassConsumesTheEvent() {
		Set<String> listeners = production.stream()
				.filter(javaClass -> javaClass.getMethods().stream()
						.anyMatch(method -> method.getRawParameterTypes().stream()
								.anyMatch(parameter -> parameter.getName().equals(EligibilityChanged.class.getName()))))
				.map(JavaClass::getName).collect(Collectors.toSet());

		assertThat(listeners).containsExactly(ROOT + ".duplicate.application.SimilarityEligibilityRefresh");
	}

	/**
	 * The announcement belongs to the boundary of an operation, which is the only
	 * place that knows whether anything really changed and can say it once. A
	 * persistence adapter or a mapper is called with one file at a time and knows
	 * neither.
	 */
	@Test
	void nothingCalledOncePerFileHoldsTheAnnouncer() {
		Set<String> holders = production.stream()
				.filter(javaClass -> javaClass.getFields().stream().anyMatch(field -> field.getRawType().getName()
						.equals(ROOT + ".duplicate.application.EligibilityAnnouncer")))
				.map(JavaClass::getName)
				.filter(name -> PER_ITEM_SUFFIXES.stream().anyMatch(name::endsWith)).collect(Collectors.toSet());

		assertThat(holders).as("This class is called once per file. The announcement belongs to whatever calls it in a"
				+ " loop - the operation - which is the only level that can say 'something changed' once.").isEmpty();
	}
}