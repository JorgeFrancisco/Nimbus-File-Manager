package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.time.Duration;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.EtaEstimate;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.EtaState;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * The one wording for a remaining time, for the screens the server renders.
 *
 * <p>
 * There used to be three, with different granularities and different silences:
 * one that could say hours, one that could not and so reported a five-hour
 * backlog in minutes, and a third written inline in the template three times
 * over. They disagreed about the same quantity.
 *
 * <p>
 * <b>It does not decide the precision.</b> The seconds arrive already rounded to
 * what the measurement can support - see {@link EtaEstimator} - because that is
 * a statement about the estimate, not about the language. This class only says
 * it out loud, and the browser's own formatter says the same thing from the same
 * keys.
 */
@Component
public class EtaLabels extends LocalizedComponent {

	/** Empty when there is nothing to say, so a caller can append it blindly. */
	public String label(EtaEstimate eta) {
		if (eta == null || eta.state() == EtaState.NOT_APPLICABLE) {
			return "";
		}

		if (eta.state() == EtaState.CALCULATING) {
			return message("eta.calculating");
		}

		Duration remaining = Duration.ofSeconds(eta.remainingSeconds());

		if (remaining.toMinutes() < 1) {
			return message("eta.lessThanMinute");
		}

		if (remaining.toHours() < 1) {
			return message("eta.minutes", remaining.toMinutes());
		}

		long minutes = remaining.toMinutesPart();

		return minutes == 0 ? message("eta.hours", remaining.toHours())
				: message("eta.hoursMinutes", remaining.toHours(), minutes);
	}
}