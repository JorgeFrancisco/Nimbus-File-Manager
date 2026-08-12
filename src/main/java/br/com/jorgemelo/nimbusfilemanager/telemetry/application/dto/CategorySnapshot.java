package br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto;

public record CategorySnapshot(long runs, long gateWaitNanos, long externalExecNanos) {
}