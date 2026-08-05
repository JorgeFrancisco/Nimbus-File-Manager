package br.com.jorgemelo.nimbusfilemanager.execution.application.dto;

import java.time.Instant;

/**
 * The last per-item progress written for one execution, so the next report can
 * tell whether writing again would say anything new.
 *
 * <p>
 * Memory in the process doing the work, and only ever an optimisation: losing
 * it costs one extra write. The truth is the column.
 */
public record ItemProgressWrite(int percent, Instant at) {
}