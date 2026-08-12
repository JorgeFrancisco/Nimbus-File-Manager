package br.com.jorgemelo.nimbusfilemanager.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.attribute.FileTime;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * The shape that keeps a filesystem instant from entering the catalog finer
 * than the catalog can remember it.
 *
 * <p>
 * NTFS counts in hundreds of nanoseconds and the column keeps microseconds, so a
 * value handed straight to the database is written as one number and read back
 * as another - and every later comparison of the two reports a change that never
 * happened. That is not hypothetical: an inventory over an already catalogued
 * library asked for thousands of content verifications of files nobody had
 * touched. {@code CatalogTimestamp} is where the value is made storable, and it
 * is applied at the edge, once.
 *
 * <p>
 * <b>What these two tests prove, and what they do not.</b> They do not prove
 * that any particular value is canonical - that would need to follow a value
 * through the code, which no static rule does honestly. What they prove is that
 * the <em>set of places where it could go wrong</em> has not grown: one test
 * fences the places that write the field, the other the places that turn a
 * {@code FileTime} into an {@code Instant}. Both fail on a new entry rather than
 * on a wrong one, which puts the decision in front of a person at the moment it
 * is made. That is the whole intent - the defect was never a bad line, it was a
 * line added somewhere nobody thought to look.
 *
 * <p>
 * One writer is deliberately out of reach of both: {@code ContentChangeWriter}
 * updates the column by native SQL, so no call to the entity appears here. It is
 * covered instead by {@code CatalogTimestampPrecisionIntegrationTest}, which
 * writes through it and reads back through JPA to prove the two agree.
 */
class CatalogTimestampArchitectureTest {

	private static final String ROOT = "br.com.jorgemelo.nimbusfilemanager";

	private static final String CATALOG_FILE = ROOT + ".shared.domain.model.CatalogFile";

	private static final String CATALOG_TIMESTAMP = ROOT + ".catalog.application.CatalogTimestamp";

	/**
	 * The classes that may set {@code CatalogFile.modifiedAt}, each because the
	 * value reaching it has already been through {@code CatalogTimestamp}:
	 * the mapper and the rebuild take it from {@code MetadataResult}, which the
	 * extractor fills from {@code DateSourceService.resolveFileSystemDates}; the
	 * move and the undo read it from disk through {@code CatalogTimestamp}
	 * themselves.
	 */
	private static final Set<String> ALLOWED_TO_WRITE_MODIFIED_AT = Set.of(
			ROOT + ".inventory.application.mapper.CatalogFileMapper",
			ROOT + ".metadata.application.MetadataRebuildService",
			ROOT + ".organization.application.OrganizationMovePersistence",
			ROOT + ".organization.application.OrganizationUndoService");

	/**
	 * The classes that may turn a {@code FileTime} into an {@code Instant} without
	 * being {@code CatalogTimestamp}. Each is here because the value never becomes
	 * a catalog date: two of them format it for a screen, and one hands the
	 * original file's time back to the file that replaced it - where truncating
	 * would damage the file rather than protect the catalog.
	 */
	private static final Set<String> ALLOWED_TO_CONVERT_FILE_TIME = Set.of(CATALOG_TIMESTAMP,
			ROOT + ".backup.application.CatalogBackupService",
			ROOT + ".media.application.explorer.FileExplorerService");

	private final JavaClasses production = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT);

	/**
	 * Whoever writes the field decides what the catalog remembers. A class added
	 * here has to be looked at, because the question it has to answer - where did
	 * this instant come from, and has it been made storable - is exactly the one
	 * that went unasked.
	 */
	@Test
	void onlyTheKnownWritersSetTheModifiedTimeOfACatalogFile() {
		Set<String> writers = production.stream().filter(CatalogTimestampArchitectureTest::writesModifiedAt)
				.map(JavaClass::getName).map(CatalogTimestampArchitectureTest::outermost).collect(Collectors.toSet());

		// Exactly, not merely within: a subset assertion is also satisfied by finding
		// nothing at all, which is what a rule like this fails as - silently, and
		// looking green. Matching the list exactly means the day it stops detecting is
		// the day it goes red.
		assertThat(writers).as("A class writing CatalogFile.modifiedAt has to be handed a value that already went"
				+ " through CatalogTimestamp - otherwise the catalog stores one instant and reads back another, and"
				+ " the next pass to compare them calls it a change. Add the class here once that is true of it.")
				.containsExactlyInAnyOrderElementsOf(ALLOWED_TO_WRITE_MODIFIED_AT);
	}

	/**
	 * The other end of the same rule. A filesystem instant becomes an application
	 * one in very few places, and every one of them is either the canonicaliser or
	 * a use that never reaches the catalog.
	 */
	@Test
	void onlyTheCanonicaliserAndUsesOutsideTheCatalogReadAFileTimeAsAnInstant() {
		Set<String> converters = production.stream().filter(CatalogTimestampArchitectureTest::convertsFileTime)
				.map(JavaClass::getName).map(CatalogTimestampArchitectureTest::outermost).collect(Collectors.toSet());

		assertThat(converters).as("A FileTime read as an Instant is a catalog date unless it demonstrably is not."
				+ " Route it through CatalogTimestamp, or add the class here saying why the value never reaches the"
				+ " catalog.").containsExactlyInAnyOrderElementsOf(ALLOWED_TO_CONVERT_FILE_TIME);
	}

	/**
	 * The setter and the builder both count. Lombok's builder assigns the field
	 * directly rather than through the setter, so a rule watching only the setter
	 * would miss {@code CatalogFile.builder().modifiedAt(...)} entirely - which is
	 * how one of the writers actually does it.
	 */
	private static boolean writesModifiedAt(JavaClass javaClass) {
		return javaClass.getMethodCallsFromSelf().stream()
				.anyMatch(call -> (call.getTargetOwner().getName().equals(CATALOG_FILE)
						&& call.getName().equals("setModifiedAt"))
						|| (call.getTargetOwner().getName().startsWith(CATALOG_FILE + "$")
								&& call.getName().equals("modifiedAt")));
	}

	private static boolean convertsFileTime(JavaClass javaClass) {
		return javaClass.getMethodCallsFromSelf().stream()
				.anyMatch(call -> call.getTargetOwner().getName().equals(FileTime.class.getName())
						&& call.getName().equals("toInstant"));
	}

	/**
	 * Lambdas and anonymous classes are compiled into the class that declares
	 * them, and it is that class the rule is about.
	 */
	private static String outermost(String name) {
		int nested = name.indexOf('$');

		return nested < 0 ? name : name.substring(0, nested);
	}
}