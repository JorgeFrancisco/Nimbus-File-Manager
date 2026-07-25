package br.com.jorgemelo.nimbusfilemanager.inventory.application.dto;

import java.nio.file.Path;
import java.util.List;

/**
 * Result of interpreting one USN record batch: the changed files under the
 * monitored root, plus whether a reconcile is needed because a rename/move
 * could not be fully resolved from the batch alone.
 */
public record Interpretation(List<Path> changedFiles, boolean reconcileNeeded) {
}