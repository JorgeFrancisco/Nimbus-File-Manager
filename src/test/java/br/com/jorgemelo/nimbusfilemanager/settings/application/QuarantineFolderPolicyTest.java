package br.com.jorgemelo.nimbusfilemanager.settings.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;

/**
 * Paths come from {@code @TempDir} because the policy normalizes what it
 * receives: a Windows drive literal would be a relative single-segment path on
 * the Linux CI and the normalization would prefix it with the runner's working
 * directory.
 */
class QuarantineFolderPolicyTest {

	private final AppSettingService appSettingService = mock(AppSettingService.class);
	private final QuarantineFolderPolicy policy = new QuarantineFolderPolicy(appSettingService);

	@Test
	void refusesAQuarantineInsideTheMonitoredLibrary(@TempDir Path tmp) {
		Path library = tmp.resolve("library");

		configure(library, null);

		String nested = library.resolve("trash").toString();

		Assertions.assertThatThrownBy(() -> policy.validateQuarantineFolder(nested))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("não pode ficar dentro da biblioteca monitorada");
	}

	/** The worst nesting of all: the quarantine root would hide the library. */
	@Test
	void refusesAQuarantineThatIsTheLibraryItself(@TempDir Path tmp) {
		Path library = tmp.resolve("library");

		configure(library, null);

		String sameFolder = library.toString();

		Assertions.assertThatThrownBy(() -> policy.validateQuarantineFolder(sameFolder))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void acceptsAQuarantineOutsideTheLibrary(@TempDir Path tmp) {
		configure(tmp.resolve("library"), null);

		Assertions.assertThatCode(() -> policy.validateQuarantineFolder(tmp.resolve("trash").toString()))
				.doesNotThrowAnyException();
	}

	@Test
	void acceptsAnyQuarantineWhileNoLibraryIsConfigured(@TempDir Path tmp) {
		configure(null, null);

		Assertions.assertThatCode(() -> policy.validateQuarantineFolder(tmp.resolve("trash").toString()))
				.doesNotThrowAnyException();
	}

	/** The new library would contain the quarantine already configured. */
	@Test
	void refusesALibraryThatWouldContainTheQuarantine(@TempDir Path tmp) {
		configure(null, tmp.resolve("library").resolve("trash"));

		String library = tmp.resolve("library").toString();

		Assertions.assertThatThrownBy(() -> policy.validateLibraryFolder(library))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("não pode ficar dentro da biblioteca monitorada");
	}

	/** The other direction: the library itself would sit under the quarantine. */
	@Test
	void refusesALibraryPlacedUnderTheQuarantine(@TempDir Path tmp) {
		Path quarantine = tmp.resolve("trash");

		configure(null, quarantine);

		String nestedLibrary = quarantine.resolve("library").toString();

		Assertions.assertThatThrownBy(() -> policy.validateLibraryFolder(nestedLibrary))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("não pode ficar dentro da pasta de quarentena");
	}

	@Test
	void acceptsALibraryThatDoesNotTouchTheQuarantine(@TempDir Path tmp) {
		configure(null, tmp.resolve("trash"));

		Assertions.assertThatCode(() -> policy.validateLibraryFolder(tmp.resolve("library").toString()))
				.doesNotThrowAnyException();
	}

	@Test
	void warnsAboutAQuarantineSharingTheVolumeWithTheLibrary(@TempDir Path tmp) {
		configure(tmp.resolve("library"), tmp.resolve("trash"));

		Assertions.assertThat(policy.warning()).hasValueSatisfying(
				warning -> Assertions.assertThat(warning).contains("mesma unidade da biblioteca"));
	}

	/**
	 * A configuration saved before this policy existed can still be nested, and
	 * saying "same drive" about it would understate the problem.
	 */
	@Test
	void warnsAboutAnAlreadyNestedQuarantineWithTheNestingText(@TempDir Path tmp) {
		Path library = tmp.resolve("library");

		configure(library, library.resolve("trash"));

		Assertions.assertThat(policy.warning()).hasValueSatisfying(
				warning -> Assertions.assertThat(warning).contains("não pode ficar dentro da biblioteca"));
	}

	@Test
	void warnsAboutAnAlreadyNestedLibraryWithTheNestingText(@TempDir Path tmp) {
		Path quarantine = tmp.resolve("trash");

		configure(quarantine.resolve("library"), quarantine);

		Assertions.assertThat(policy.warning()).hasValueSatisfying(
				warning -> Assertions.assertThat(warning).contains("não pode ficar dentro da pasta de quarentena"));
	}

	@Test
	void saysNothingWhileEitherFolderIsUnset(@TempDir Path tmp) {
		configure(tmp.resolve("library"), null);

		Assertions.assertThat(policy.warning()).isEmpty();

		configure(null, tmp.resolve("trash"));

		Assertions.assertThat(policy.warning()).isEmpty();
	}

	/**
	 * Windows-only because two volumes cannot be told apart on the Linux CI, where
	 * every absolute path shares the single "/" root. The drive literals are safe
	 * here for the same reason the class comment forbids them elsewhere: on Windows
	 * they are already absolute, so normalizing them changes nothing.
	 */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void saysNothingWhenTheQuarantineIsOnAnotherVolume() {
		configure(Path.of("D:\\library"), Path.of("C:\\trash"));

		Assertions.assertThat(policy.warning()).isEmpty();
	}

	private void configure(Path library, Path quarantine) {
		when(appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, ""))
				.thenReturn(library == null ? "" : library.toString());
		when(appSettingService.stringValue(SettingsConstants.TRASH_FOLDER, ""))
				.thenReturn(quarantine == null ? "" : quarantine.toString());
	}
}