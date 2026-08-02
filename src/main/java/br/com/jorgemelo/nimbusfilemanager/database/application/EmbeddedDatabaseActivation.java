package br.com.jorgemelo.nimbusfilemanager.database.application;

import static br.com.jorgemelo.nimbusfilemanager.database.domain.enums.EmbeddedDatabaseDecision.BINARIES_MISSING;
import static br.com.jorgemelo.nimbusfilemanager.database.domain.enums.EmbeddedDatabaseDecision.DISABLED_BY_CONFIGURATION;
import static br.com.jorgemelo.nimbusfilemanager.database.domain.enums.EmbeddedDatabaseDecision.ENABLED;
import static br.com.jorgemelo.nimbusfilemanager.database.domain.enums.EmbeddedDatabaseDecision.EXTERNAL_DATABASE_CONFIGURED;
import static br.com.jorgemelo.nimbusfilemanager.database.domain.enums.EmbeddedDatabaseDecision.UNSUPPORTED_PLATFORM;

import java.util.Locale;

import br.com.jorgemelo.nimbusfilemanager.database.domain.enums.EmbeddedDatabaseDecision;

/**
 * The one place that answers whether this run manages its own PostgreSQL.
 *
 * <p>
 * Three signals can each argue for a different answer, so their order is fixed
 * here and nowhere else:
 *
 * <ol>
 * <li>the explicit setting - the only knob whose whole job is to answer this
 * question, so it wins in both directions;</li>
 * <li>a database configured by hand - a host or a full url names an owner for
 * the data, and taking it over would be the worst kind of helpful;</li>
 * <li>otherwise on - nobody who runs this application installed a database
 * server first, and that is as true of a clone as it is of an installed
 * copy.</li>
 * </ol>
 *
 * <p>
 * The installation marker used to be the third signal, and a run without one
 * was assumed to be a developer machine with its own PostgreSQL. That
 * assumption is what made a fresh clone unable to start: it fell back to a
 * server nobody had installed. Now the two paths are the same one, which is
 * also the only way the packaged behaviour gets exercised before it is
 * packaged. The suite is the exception, and it says so explicitly rather than
 * being guessed at - every {@code @SpringBootTest} brings its own container.
 *
 * <p>
 * The resolved {@code spring.datasource.url} deliberately means nothing here.
 * The packaged properties always define it, placeholders and all, so it is
 * present on every run and cannot distinguish anything - reading it as "an
 * external database was configured" would disable the embedded cluster
 * everywhere, including the installation it exists for. What does count is a
 * value someone set themselves, which is why the caller passes the environment
 * variable rather than the property.
 *
 * <p>
 * The last two outcomes are refusals of something that was asked for. They stay
 * distinct from a plain "off" so the log says which of the two happened; the
 * caller falls back to the configured connection either way, because an
 * installed copy that refuses to start helps nobody.
 */
public final class EmbeddedDatabaseActivation {

	private static final String WINDOWS = "windows";

	private EmbeddedDatabaseActivation() {
	}

	/**
	 * @param configured the explicit setting, or {@code null} when silent
	 * @param externalDatabase a host or url configured by hand, or {@code null}
	 * when nothing was
	 * @param operatingSystem the {@code os.name} of this run
	 * @param binariesPresent whether the packaged PostgreSQL is where it belongs
	 */
	public static EmbeddedDatabaseDecision decide(String configured, String externalDatabase, String operatingSystem,
			boolean binariesPresent) {
		// Anything other than "true" counts as off, so a typo turns the cluster off
		// rather than on - the direction that cannot lose data.
		if (isPresent(configured)) {
			return Boolean.parseBoolean(configured.trim()) ? verifySupport(operatingSystem, binariesPresent)
					: DISABLED_BY_CONFIGURATION;
		}

		if (isPresent(externalDatabase)) {
			return EXTERNAL_DATABASE_CONFIGURED;
		}

		return verifySupport(operatingSystem, binariesPresent);
	}

	private static EmbeddedDatabaseDecision verifySupport(String operatingSystem, boolean binariesPresent) {
		if (!isWindows(operatingSystem)) {
			return UNSUPPORTED_PLATFORM;
		}

		return binariesPresent ? ENABLED : BINARIES_MISSING;
	}

	private static boolean isWindows(String operatingSystem) {
		return operatingSystem != null && operatingSystem.toLowerCase(Locale.ROOT).contains(WINDOWS);
	}

	private static boolean isPresent(String value) {
		return value != null && !value.isBlank();
	}
}