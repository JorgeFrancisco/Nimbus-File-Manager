package br.com.jorgemelo.nimbusfilemanager.shared.application;

import java.util.List;
import java.util.Locale;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The words the tray menu shows, read from the bundles without a
 * {@code MessageSource}.
 *
 * <p>
 * The icon exists before Spring does - that is the point of it, since a first
 * start spends minutes fetching a database server before any context exists -
 * so these labels cannot come through the usual path. Reading the same files
 * directly is what keeps them from becoming a second copy of the text.
 */
class TrayTextTest {

	private static final List<String> KEYS = List.of("tray.open", "tray.logs", "tray.workspace", "tray.exit",
			"tray.ready", "tray.tooltip.starting", "tray.tooltip.running");

	@Test
	void everyLabelTheMenuAsksForExistsInBothLanguages() {
		for (String key : KEYS) {
			Assertions.assertThat(new TrayText(Locale.of("pt", "BR")).get(key)).as(key).isNotBlank();
			Assertions.assertThat(new TrayText(Locale.ENGLISH).get(key)).as(key).isNotBlank();
		}
	}

	/** Any language that is not Portuguese reads the English bundle. */
	@Test
	void answersInThePortugueseBundleOnlyForPortuguese() {
		Assertions.assertThat(new TrayText(Locale.of("pt", "BR")).get("tray.exit")).isEqualTo("Sair");
		Assertions.assertThat(new TrayText(Locale.ENGLISH).get("tray.exit")).isEqualTo("Exit");
		Assertions.assertThat(new TrayText(Locale.of("de")).get("tray.exit")).isEqualTo("Exit");
	}

	/**
	 * A label reading {@code tray.exit} on somebody's desktop is worse than a
	 * failure that names the missing key, which is what the
	 * {@code MessageSource} does everywhere else.
	 */
	@Test
	void refusesAKeyThatIsNotInTheBundle() {
		TrayText text = new TrayText(Locale.ENGLISH);

		Assertions.assertThatIllegalStateException().isThrownBy(() -> text.get("tray.absent"))
				.withMessageContaining("tray.absent");
	}

	/**
	 * A bundle that did not make it into the packaged jar has to say so. The tray
	 * is installed before anything is listening, so the alternative is an icon
	 * whose every label is missing and no clue as to why.
	 */
	@Test
	void refusesABundleThatIsNotOnTheClasspath() {
		Assertions.assertThatIllegalStateException().isThrownBy(() -> new TrayText("/messages_absent.properties"))
				.withMessageContaining("/messages_absent.properties");
	}

	/**
	 * Loopback rather than the host name: this menu belongs to whoever is sitting
	 * at the machine, and that is the address which always resolves for them.
	 */
	@Test
	void opensTheAddressThatAlwaysResolvesLocally() {
		Assertions.assertThat(TrayText.url(8088)).isEqualTo("http://localhost:8088/");
	}
}