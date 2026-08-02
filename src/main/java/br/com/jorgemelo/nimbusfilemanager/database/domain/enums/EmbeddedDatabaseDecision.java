package br.com.jorgemelo.nimbusfilemanager.database.domain.enums;

/**
 * Why the embedded database is running, or why it is not. Every outcome is
 * distinct because each one reads differently in a log: a copy that silently
 * fell back to an external database and one whose binaries never arrived look
 * identical through a boolean.
 */
public enum EmbeddedDatabaseDecision {

	/** The application manages its own cluster. */
	ENABLED(true),

	/** Turned off explicitly, whatever else would have applied. */
	DISABLED_BY_CONFIGURATION(false),

	/** A database host was named, so someone else's server owns the data. */
	EXTERNAL_DATABASE_CONFIGURED(false),

	/** Asked for, but this slice only ships the Windows binaries. */
	UNSUPPORTED_PLATFORM(false),

	/** Asked for, and the packaged binaries are not where they should be. */
	BINARIES_MISSING(false);

	private final boolean active;

	EmbeddedDatabaseDecision(boolean active) {
		this.active = active;
	}

	public boolean active() {
		return active;
	}
}