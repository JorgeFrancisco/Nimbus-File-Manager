package br.com.jorgemelo.nimbusfilemanager.settings.application.dto;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.EtaEstimate;
import br.com.jorgemelo.nimbusfilemanager.settings.domain.enums.ToolInstallPhase;

public record ToolInstallSnapshot(ToolInstallPhase phase, long bytesDone, long bytesTotal, double percent,
		EtaEstimate eta) {

	public boolean downloading() {
		return phase == ToolInstallPhase.DOWNLOADING;
	}

	public boolean extracting() {
		return phase == ToolInstallPhase.EXTRACTING;
	}
}