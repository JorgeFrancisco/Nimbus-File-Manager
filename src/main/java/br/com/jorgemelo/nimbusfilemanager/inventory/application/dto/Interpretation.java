package br.com.jorgemelo.nimbusfilemanager.inventory.application.dto;

import java.util.List;

/**
 * Result of interpreting one USN record batch: what the journal said happened
 * under the monitored root, plus whether a reconcile is needed anyway because
 * something in the batch could not be expressed as changes to single entries.
 *
 * @param changes what was observed, in the order it was observed.
 * @param reconcileNeeded whether the batch left something only a full
 * comparison against the catalog can settle.
 */
public record Interpretation(List<FileSystemChange> changes, boolean reconcileNeeded) {
}