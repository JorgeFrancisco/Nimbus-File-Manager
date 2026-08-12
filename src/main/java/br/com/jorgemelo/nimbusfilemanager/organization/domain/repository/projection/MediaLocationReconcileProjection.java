package br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.projection;

import java.time.Instant;

/**
 * One catalogued place a reconciliation compares against the disk, with the two
 * cheap facts the comparison needs.
 *
 * <p>
 * The size and the modification time travel with the path because the walk is
 * handed them for free by the operating system, and comparing them is the whole
 * of what a pass can say about content without opening anything. A pass that
 * carried only the path could ask whether a file was still there and nothing
 * about whether it was still the same file.
 */
public interface MediaLocationReconcileProjection {

	Long getCatalogFileId();

	String getCurrentPath();

	Long getSizeBytes();

	Instant getModifiedAt();
}