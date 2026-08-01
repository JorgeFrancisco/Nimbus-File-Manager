package br.com.jorgemelo.nimbusfilemanager.settings.application.dto;

import br.com.jorgemelo.nimbusfilemanager.settings.domain.enums.ToolInstallPhase;

public record ToolInstallSnapshot(ToolInstallPhase phase, long bytesDone, long bytesTotal, double percent,
		long etaSeconds) {

	public boolean downloading() {
		return phase == ToolInstallPhase.DOWNLOADING;
	}

	public boolean extracting() {
		return phase == ToolInstallPhase.EXTRACTING;
	}
}