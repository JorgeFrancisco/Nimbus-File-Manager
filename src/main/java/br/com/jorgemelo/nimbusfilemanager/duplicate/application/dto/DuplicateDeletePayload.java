package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Which duplicates to send to quarantine.
 *
 * <p>
 * The files travel here rather than in a column because a selection is a set
 * the user ticked one by one, and no folder describes it. What the row carries
 * is the quarantine root, which is where they are all going and what the worker
 * locks first.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DuplicateDeletePayload(Integer schemaVersion, List<UUID> publicIds) {
}