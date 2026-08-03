package br.com.jorgemelo.nimbusfilemanager.update.application.dto;

import br.com.jorgemelo.nimbusfilemanager.update.domain.enums.UpdatePhase;

/**
 * What the screen shows while an update installs.
 *
 * @param phase where it is
 * @param running whether anything is happening at all, so the screen does not
 * have to infer it from the phase
 * @param bytesDone bytes fetched so far
 * @param bytesTotal what the server announced, or -1 when it announced nothing
 * @param percent 0-100, or -1 when it cannot be known
 * @param etaSeconds estimate, or -1 when it cannot be known
 * @param message the reason it stopped, already localized, or {@code null}
 */
public record UpdateInstallSnapshot(UpdatePhase phase, boolean running, long bytesDone, long bytesTotal, double percent,
		long etaSeconds, String message) {
}