package br.com.jorgemelo.nimbusfilemanager.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.shared.application.catalog.CatalogCollectionMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.application.library.LibraryFileMutations;

/**
 * The boundary that keeps a user's files from being changed anywhere except
 * through the ports, checked rather than remembered.
 *
 * <p>
 * These rules exist because the alternative is a convention, and a convention
 * is a thing people know until they are new, in a hurry, or an assistant that
 * has never read the discussion. What they cannot check is what a
 * {@code Path} points at while the program runs - so they check the shape
 * around it: who may hold the capability, who may reach whoever holds it, and
 * which classes are allowed to call {@code Files} at all. The classification of
 * every one of those classes is in VIII.142 of
 * {@code docs/archive/a8-auditoria-de-aderencia.md}.
 */
class MutationBoundaryArchitectureTest {

	private static final String ROOT = "br.com.jorgemelo.nimbusfilemanager";

	/**
	 * Reachable from an {@code ExecutionJobHandler}, which is what makes holding
	 * the capability legitimate. Listed rather than derived: see
	 * {@link #theWorkerSideConsumersAreReachableFromAHandler()} for what is
	 * checked about this list and what is not.
	 */
	private static final List<String> WORKER_CONSUMERS = List.of(
			ROOT + ".organization.application.OrganizationExecutor",
			ROOT + ".organization.application.OrganizationUndoService",
			ROOT + ".organization.application.EmptyDirectoryCleaner",
			ROOT + ".conversion.application.ConversionFilePlacement",
			ROOT + ".conversion.application.ConversionCommitService",
			ROOT + ".quarantine.application.QuarantineIntakeService",
			ROOT + ".quarantine.application.QuarantinePurgeService",
			ROOT + ".quarantine.application.QuarantineService",
			ROOT + ".settings.application.LibraryCatalogCleanupService",
			ROOT + ".catalog.application.CatalogFileRetentionService",
			ROOT + ".media.application.explorer.ExplorerRenameService",
			ROOT + ".media.application.explorer.DefaultExplorerFileSystem");

	/**
	 * Classes that write to the file system outside the ports, each one an
	 * artefact this product owns and can regenerate - a thumbnail, a temporary
	 * conversion, a downloaded dataset, the embedded database, the installer.
	 * Adding a name here is meant to be an argument, not a formality: the question
	 * it answers is "whose file is this?", and if the answer is "the user's", the
	 * class belongs behind the port instead.
	 */
	private static final Set<String> WORKSPACE_AND_INFRASTRUCTURE_WRITERS = Set.of(
			ROOT + ".backup.application.BackupDelivery", ROOT + ".backup.application.BackupFolderResolver",
			ROOT + ".backup.application.CatalogBackupService",
			ROOT + ".backup.infrastructure.PostgresDumpProcessRunner",
			ROOT + ".conversion.application.ConversionFileNaming",
			ROOT + ".database.application.ClusterPropertiesStore",
			ROOT + ".database.application.EmbeddedDatabaseInstaller",
			ROOT + ".database.infrastructure.PostgresBuildSource",
			ROOT + ".database.infrastructure.PostgresProcessRunner",
			ROOT + ".geolocation.application.boundary.GeoDatasetRemoval",
			ROOT + ".geolocation.infrastructure.boundary.GeoBoundariesSource",
			ROOT + ".metadata.application.PhotoPerceptualHashService",
			ROOT + ".security.application.FirstAccessCredential",
			ROOT + ".settings.application.ExternalToolInstaller",
			ROOT + ".settings.application.LibraryCatalogCleanupService",
			ROOT + ".settings.infrastructure.tools.FfmpegBuildSource",
			ROOT + ".shared.infrastructure.config.WorkspaceBootstrapListener",
			ROOT + ".thumbnail.application.PhotoThumbnailService",
			ROOT + ".thumbnail.application.VideoThumbnailService",
			ROOT + ".update.application.UpdateInstallService",
			ROOT + ".update.application.UpdateInstallation",
			ROOT + ".update.infrastructure.HttpReleaseDownloader",
			ROOT + ".update.infrastructure.UpdateInstallProcessRunner");

	/**
	 * The classes that may touch a user's file directly. Two, and they are the
	 * port's implementation and the verified-move primitive it delegates to.
	 */
	private static final Set<String> LIBRARY_MUTATORS = Set.of(
			ROOT + ".organization.application.SecureLibraryFiles",
			ROOT + ".organization.application.SecureFileMove");

	private final JavaClasses production = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT);

	/**
	 * P1, and what it can honestly claim. It cannot know that a {@code Path}
	 * points at somebody's photo - that is a runtime value - so it checks the
	 * complement: every class that calls a mutating {@code Files} method is either
	 * a declared library mutator, or a declared workspace/infrastructure writer.
	 * A class that is neither fails, and the failure asks for the decision rather
	 * than for a name to be added.
	 */
	@Test
	void everyClassThatWritesToDiskIsClassified() {
		Set<String> writers = production.stream()
				.filter(javaClass -> javaClass.getDirectDependenciesFromSelf().stream()
						.anyMatch(dependency -> dependency.getTargetClass().getName().equals("java.nio.file.Files")))
				.map(JavaClass::getName).filter(name -> !name.contains("$"))
				.collect(Collectors.toSet());

		Set<String> mutatorsOfSomeoneElsesFiles = writers.stream()
				.filter(name -> !LIBRARY_MUTATORS.contains(name))
				.filter(name -> !WORKSPACE_AND_INFRASTRUCTURE_WRITERS.contains(name))
				.filter(this::mutatesThroughFiles).collect(Collectors.toSet());

		assertThat(mutatorsOfSomeoneElsesFiles)
				.as("These classes call java.nio.file.Files and are in neither list. If the path belongs to the user,"
						+ " the operation belongs behind LibraryFileMutations; if it is a thumbnail, a temporary or"
						+ " an install artefact, add it to WORKSPACE_AND_INFRASTRUCTURE_WRITERS with that reason."
						+ " See VIII.142.")
				.isEmpty();
	}

	/**
	 * P2. The capability is granted by injection, so what this checks is who
	 * injects it: the worker-side consumers and the port's own implementation,
	 * nobody else.
	 *
	 * <p>
	 * There is no exception list any more. There was one for the length of the
	 * migration - a class the application still executed itself, each with the
	 * slice that would remove it - and the last entry went with 4.2.8. The rule is
	 * absolute now, which is the point of having finished.
	 */
	@Test
	void onlyDeclaredConsumersHoldAMutationPort() {
		noClasses().that().haveNameNotMatching(allowedConsumerPattern()).should().dependOnClassesThat()
				.areAssignableTo(LibraryFileMutations.class)
				.because("holding LibraryFileMutations is the capability to change a user's file, and it is granted"
						+ " by injection: a class that is not reachable from an ExecutionJobHandler must not have it."
						+ " Move the capability to the worker - there is no longer a list to add it to.")
				.check(production);

		noClasses().that().haveNameNotMatching(allowedConsumerPattern()).and()
				.areNotAssignableTo(CatalogCollectionMutations.class).should().dependOnClassesThat()
				.areAssignableTo(CatalogCollectionMutations.class)
				.because("the same rule for the collection itself: purging catalogued files or forgetting a library"
						+ " destroys history, and that is workload rather than ordinary persistence. Converging what"
						+ " the operating system already did is a different authority and has a port of its own -"
						+ " which is why the watcher can hold that one and cannot reach this.")
				.check(production);
	}

	/**
	 * P3, and it is deliberately about a different thing from P2: P2 says who may
	 * <em>hold</em> the capability, this says who may <em>reach</em> it. A screen
	 * that reaches a mutation port - through however many services - is a screen
	 * that can change files during a request, which is the shape the whole
	 * migration exists to end.
	 */
	@Test
	void noScreenReachesAMutationPort() {
		Set<String> reachable = reachableFrom(deliveryClasses());

		Set<String> offenders = reachable.stream().filter(this::holdsAPort).collect(Collectors.toSet());

		assertThat(offenders)
				.as("A controller reaches these classes, and they hold a mutation port. The capability belongs behind"
						+ " an ExecutionJobHandler: queue the intention instead of executing it in the request.")
				.isEmpty();
	}

	/** P5. */
	@Test
	void onlyInfrastructureNamesTheDatabaseDriver() {
		noClasses().that().resideOutsideOfPackage("..infrastructure..").should().dependOnClassesThat()
				.resideInAPackage("org.postgresql..")
				.because("application and domain are blind to which database this is. The one adapter that needs the"
						+ " driver by name - the LISTEN/NOTIFY channel - lives in infrastructure, which is where a"
						+ " dependency on a concrete technology belongs. Moving application code into infrastructure"
						+ " to get around this rule moves the responsibility too, and that is the thing to avoid.")
				.check(production);
	}

	/**
	 * The migration is over, stated as a test.
	 *
	 * <p>
	 * Every class that holds a mutation port is either worker-side work or the
	 * port's own implementation. Nothing is exempt, nothing is pending, and there
	 * is no list to add a name to - which is what makes reintroducing the shape
	 * this phase removed a failing build rather than a line in an allowlist.
	 */
	@Test
	void noCapabilityIsStillBeingMigrated() {
		Set<String> allowed = new HashSet<>(WORKER_CONSUMERS);

		allowed.addAll(LIBRARY_MUTATORS);
		allowed.add(ROOT + ".shared.application.catalog.CollectionCatalogMutations");

		Set<String> holders = production.stream().map(JavaClass::getName).filter(this::holdsAPort)
				.collect(Collectors.toSet());

		assertThat(holders).as("Every holder of a mutation port has to be worker-side work or the port itself."
				+ " A new one means a capability was given to the application; a missing one means the list is stale.")
				.isSubsetOf(allowed);
	}

	/**
	 * What the worker-side list claims, checked in the direction that can be:
	 * every class on it really is reached by a handler. The other direction - that
	 * nothing missing from the list is reachable - is P2's job, and is why the two
	 * tests are not one.
	 */
	@Test
	void theWorkerSideConsumersAreReachableFromAHandler() {
		Set<String> reachable = reachableFrom(production.stream()
				.filter(javaClass -> javaClass.getAllRawInterfaces().stream()
						.anyMatch(each -> each.getSimpleName().equals("ExecutionJobHandler")))
				.map(JavaClass::getName).collect(Collectors.toSet()));

		assertThat(reachable).as("A class listed as worker-side is not reachable from any ExecutionJobHandler."
				+ " Either it moved, or it is no longer worker work and the list is stale.")
				.containsAll(WORKER_CONSUMERS);
	}

	/**
	 * A handler that can reach the port which changes the user's files runs under
	 * the path locks, and says so.
	 *
	 * <p>
	 * {@code requiresPathLock()} exists because some work is a query rather than a
	 * folder - draining a backlog, grouping what is fingerprinted, deleting catalog
	 * rows past their retention - and making those invent a path would have them
	 * wait for, and block, work they have nothing to do with. The danger is the
	 * other direction: a handler that does move files declaring it needs no lock,
	 * and thereby leaving the exclusion. That is what this refuses.
	 *
	 * <p>
	 * The catalog port alone does not count. Deleting rows past their retention
	 * excludes nobody from a tree, and demanding a lock for it would be asking a
	 * question about disk that the work does not ask.
	 */
	@Test
	void everyHandlerThatCanTouchTheUsersFilesRunsUnderThePathLocks() {
		Set<String> offenders = production.stream().filter(this::isHandler)
				.filter(handler -> reachableFrom(Set.of(handler.getName())).stream().anyMatch(this::holdsTheFilePort))
				.filter(handler -> !needsThePathLock(handler)).map(JavaClass::getName).collect(Collectors.toSet());

		assertThat(offenders).as("A handler that can reach LibraryFileMutations answers requiresPathLock() = false,"
				+ " which would let it move the user's files outside the exclusion every other execution obeys.")
				.isEmpty();
	}

	private boolean isHandler(JavaClass javaClass) {
		return !javaClass.getModifiers().contains(JavaModifier.ABSTRACT) && javaClass.getAllRawInterfaces().stream()
				.anyMatch(each -> each.getSimpleName().equals("ExecutionJobHandler"));
	}

	/**
	 * The answer the dispatcher will get, asked of the handler itself.
	 *
	 * <p>
	 * It used to be read off the shape of the class - a handler that declared no
	 * {@code requiresPathLock} was taken to mean yes, because that is the default.
	 * Which is true of the class and false of the object: a concrete handler
	 * extending an abstract one that answers no declares nothing itself and
	 * inherits the no, and the rule read it as a yes. Two handlers are exactly
	 * that shape today, and a mutating third could be written tomorrow and pass.
	 *
	 * <p>
	 * So it is called rather than inferred. The instance exists only to be asked
	 * this one question - the constructor never runs, and no dependency of the
	 * handler is needed to answer it - which is what makes calling cheap enough to
	 * prefer over reading the declaration.
	 */
	private boolean needsThePathLock(JavaClass handler) {
		return ((ExecutionJobHandler) mock(handler.reflect(), CALLS_REAL_METHODS)).requiresPathLock();
	}

	private boolean holdsTheFilePort(String className) {
		return production.stream().filter(javaClass -> javaClass.getName().equals(className))
				.anyMatch(javaClass -> javaClass.getDirectDependenciesFromSelf().stream()
						.map(dependency -> dependency.getTargetClass().getName())
						.anyMatch(LibraryFileMutations.class.getName()::equals));
	}

	/**
	 * Holding a port is depending on one from outside it. The adapter that
	 * implements the port depends on it by definition, and calling that a breach
	 * would make the rule impossible to satisfy: something has to implement it.
	 */
	private boolean holdsAPort(String className) {
		return production.stream().filter(javaClass -> javaClass.getName().equals(className))
				.filter(javaClass -> !javaClass.isAssignableTo(CatalogCollectionMutations.class))
				.filter(javaClass -> !javaClass.isAssignableTo(LibraryFileMutations.class))
				.anyMatch(javaClass -> javaClass.getDirectDependenciesFromSelf().stream()
						.map(dependency -> dependency.getTargetClass().getName())
						.anyMatch(name -> name.equals(LibraryFileMutations.class.getName())
								|| name.equals(CatalogCollectionMutations.class.getName())));
	}

	/**
	 * Reads of {@code Files} are not mutations - listing a folder, asking whether
	 * a path exists, streaming bytes to hash them. Only the methods that change
	 * something count, which is what keeps this rule about writing.
	 */
	private boolean mutatesThroughFiles(String className) {
		Set<String> mutating = Set.of("move", "delete", "deleteIfExists", "copy", "write", "writeString",
				"newOutputStream", "createFile", "createDirectory", "createDirectories", "createTempFile",
				"createTempDirectory", "setLastModifiedTime", "setAttribute", "setPosixFilePermissions");

		return production.stream().filter(javaClass -> javaClass.getName().equals(className))
				.anyMatch(javaClass -> javaClass.getMethodCallsFromSelf().stream()
						.filter(call -> call.getTargetOwner().getName().equals("java.nio.file.Files"))
						.anyMatch(call -> mutating.contains(call.getName())));
	}

	private Set<String> deliveryClasses() {
		return production.stream()
				.filter(javaClass -> javaClass.getPackageName().contains(".infrastructure.rest")
						|| javaClass.getPackageName().contains(".infrastructure.web"))
				.map(JavaClass::getName).collect(Collectors.toSet());
	}

	/**
	 * Transitive closure over what each class <em>holds</em> - the types of its
	 * fields - rather than over every type it mentions.
	 *
	 * <p>
	 * The distinction is the difference between a rule and a tautology. ArchUnit's
	 * full dependency graph includes parameter types, return types, exceptions and
	 * enums, and in one Spring application that graph connects a controller to
	 * almost every class there is: asked that way, "does a screen reach a mutation
	 * port" is true of everything and proves nothing. What a screen actually
	 * <em>uses</em> is what it was injected with, and what that was injected with,
	 * which is exactly the chain of fields. That is also the chain the capability
	 * travels along, since the port is granted by injection.
	 *
	 * <p>
	 * A field declared as an interface is followed to whatever implements it,
	 * which is not a refinement but the point: the container injects the
	 * implementation, so a class holding {@code ExplorerFileSystem} holds
	 * everything the class behind it can do. Stopping at the interface would let
	 * any capability be hidden behind one.
	 *
	 * <p>
	 * Its blind spot, stated rather than hidden: a dependency held in a generic
	 * collection - {@code Map<ExecutionType, ExecutionJobHandler>} is the one that
	 * exists here - is seen as the collection. Nothing reaches a mutation port only
	 * that way today, and a rule that pretended otherwise would be claiming more
	 * than it checks.
	 */
	private Set<String> reachableFrom(Set<String> roots) {
		Set<String> seen = new HashSet<>(roots);

		Deque<String> pending = new ArrayDeque<>(roots);

		while (!pending.isEmpty()) {
			String current = pending.poll();

			production.stream().filter(javaClass -> javaClass.getName().equals(current))
					.flatMap(javaClass -> javaClass.getFields().stream())
					.map(field -> field.getRawType().getName())
					.filter(name -> name.startsWith(ROOT)).flatMap(this::withImplementations)
					.filter(seen::add).forEach(pending::add);
		}

		return seen;
	}

	/** A held type, and - when it is an interface - whatever stands behind it. */
	private Stream<String> withImplementations(String typeName) {
		return Stream.concat(Stream.of(typeName),
				production.stream()
						.filter(javaClass -> javaClass.getAllRawInterfaces().stream()
								.anyMatch(each -> each.getName().equals(typeName)))
						.map(JavaClass::getName));
	}

	private String allowedConsumerPattern() {
		Set<String> allowed = new HashSet<>(WORKER_CONSUMERS);

		allowed.addAll(LIBRARY_MUTATORS);
		allowed.add(ROOT + ".shared.application.catalog.CollectionCatalogMutations");

		return allowed.stream().map(Pattern::quote).collect(Collectors.joining("|"));
	}
}