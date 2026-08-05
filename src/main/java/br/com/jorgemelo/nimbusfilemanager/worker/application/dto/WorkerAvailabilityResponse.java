package br.com.jorgemelo.nimbusfilemanager.worker.application.dto;

import java.time.LocalDateTime;

/**
 * Whether anything is alive to run queued work, as a fact rather than as a
 * verdict.
 *
 * @param available whether at least one worker was heard from recently enough
 * @param instances how many were - one is the normal answer, and anything else
 * is worth seeing rather than being rounded to "yes"
 * @param lastSeenAt when the most recent of them was last heard from, null when
 * none ever was. Present even when {@code available} is false, because "nothing
 * since 03:12" and "nothing, ever" are different problems
 */
public record WorkerAvailabilityResponse(boolean available, int instances, LocalDateTime lastSeenAt) {
}