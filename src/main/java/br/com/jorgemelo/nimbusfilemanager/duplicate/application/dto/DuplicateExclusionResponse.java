package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

/**
 * Result of a "hide from duplicate comparison" action: {@code created} is true
 * when a judgement was written - a first one, or one about content the standing
 * judgement was not about - and false when nothing changed, which is either the
 * same judgement already on record or no catalogued item left to judge.
 */
public record DuplicateExclusionResponse(boolean created) {
}