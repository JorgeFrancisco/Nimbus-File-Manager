package br.com.jorgemelo.nimbusfilemanager.execution.application.dto;

/**
 * Which taking of an execution a worker is acting on behalf of.
 *
 * <p>
 * The worker name alone does not say it. A worker that lost a row to recovery
 * can claim the same row again moments later, under the same name, and a write
 * left over from the first taking must not be accepted by the second. The
 * attempt number is what tells the two apart: it moves once per taking that got
 * as far as starting for real, and it never moves back.
 *
 * @param executionId the row this possession is over
 * @param workerId who took it
 * @param claimCount the attempt number written when this taking began
 */
public record ExecutionPossession(long executionId, String workerId, int claimCount) {
}