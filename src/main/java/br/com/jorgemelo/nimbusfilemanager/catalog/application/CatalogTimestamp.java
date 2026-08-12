package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * A moment observed on disk, as the catalog is able to remember it.
 *
 * <p>
 * Filesystems keep time more finely than the catalog does: NTFS counts in
 * hundreds of nanoseconds, ext4 in nanoseconds, and the column behind
 * {@code CatalogFile.modifiedAt} keeps microseconds. Handing the finer value
 * straight to the database means writing one number and reading back another,
 * and every comparison of the two then reports a change that never happened -
 * which is exactly what an inventory did on its second pass over an already
 * catalogued library, asking for thousands of content verifications of files
 * nobody had touched.
 *
 * <p>
 * <b>Truncated, not rounded.</b> Rounding is what the database and the driver
 * happen to do when handed something they cannot store, and they do not even
 * agree with each other on an exact tie - the server rounds half to even, the
 * driver half up. Making the domain reproduce either would turn an accident of
 * two libraries into a rule of this product. Truncation is a decision instead:
 * the catalog keeps the microsecond it observed and discards what it cannot
 * hold, always the same way, with nothing to look up.
 *
 * <p>
 * <b>Applied at the edge, once.</b> Every timestamp that may become
 * {@code CatalogFile.modifiedAt} passes through here on its way in, so both
 * writers store a value that is already exact and the comparisons stay plain
 * equality - none of them has to know what a database or a driver does with a
 * nanosecond.
 *
 * <p>
 * The cost is deliberate and bounded: two moments less than a microsecond apart
 * are the same moment as far as the catalog is concerned. A file cannot be
 * modified twice inside a microsecond in any way a filesystem would report
 * differently, and every check that matters here pairs the timestamp with the
 * size.
 */
public final class CatalogTimestamp {

	private CatalogTimestamp() {
	}

	/** The observed moment, at the precision the catalog stores. */
	public static Instant observed(Instant fromFilesystem) {
		return fromFilesystem == null ? null : fromFilesystem.truncatedTo(ChronoUnit.MICROS);
	}

	/** The same, for callers that hold the attribute rather than the instant. */
	public static Instant observed(FileTime fromFilesystem) {
		return fromFilesystem == null ? null : observed(fromFilesystem.toInstant());
	}
}