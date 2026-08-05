package br.com.jorgemelo.nimbusfilemanager.duplicate.calibration;

/**
 * Every eligible hash, flattened four {@code long} per photo, with the count of
 * rows that could not be read as 256 bits. The count travels alongside because
 * a scan over a population silently missing rows would report a distribution of
 * something other than the library.
 */
public record LoadedHashes(long[] packed, long[] ids, int count, int malformed) {

	public long pairs() {
		return (long) count * (count - 1) / 2;
	}

	/** Bytes the flattened array occupies, which is the whole working set. */
	public long bytes() {
		return (long) packed.length * Long.BYTES;
	}
}