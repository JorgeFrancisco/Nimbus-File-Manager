package br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto;

import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.Phase;

public record Snapshot(Phase phase, String stepLabel, long bytesDone, long bytesTotal, double percent, long etaSeconds,
		long recordsImported) {

	public boolean downloading() {
		return phase == Phase.DOWNLOADING;
	}

	public boolean importing() {
		return phase == Phase.IMPORTING;
	}
}