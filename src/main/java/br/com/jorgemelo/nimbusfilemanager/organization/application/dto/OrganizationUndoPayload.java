package br.com.jorgemelo.nimbusfilemanager.organization.application.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Which execution is being reversed.
 *
 * <p>
 * It is the one thing an undo needs and the one thing the row cannot say: the
 * folders in the columns are the pair the worker locks, and they are the
 * original's two ends swapped over, which does not identify the run they came
 * from. The movements to reverse are read from that run when the undo starts,
 * so what travels here is an identity and nothing else.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrganizationUndoPayload(Integer schemaVersion, Long undoneExecutionId) {
}