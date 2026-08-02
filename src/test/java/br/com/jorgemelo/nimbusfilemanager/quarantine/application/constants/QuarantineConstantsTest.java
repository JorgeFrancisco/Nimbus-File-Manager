package br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants;

import java.util.Arrays;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;

/**
 * Every way into the quarantine folder has to be a way out of it too.
 *
 * <p>
 * Derived from the enum rather than repeating the set, because a test that
 * listed the reasons by hand would have been written from the same set it is
 * meant to guard, and would have passed while
 * {@link MovementReason#USER_QUARANTINED} was missing - which is how hundreds of
 * files came to sit in the folder, invisible to the screen that lists them and
 * unreachable by the restore and the purge that share the same set.
 */
class QuarantineConstantsTest {

	@Test
	void everyIntakeReasonIsOneTheQuarantineScreenOwns() {
		List<MovementReason> intake = Arrays.stream(MovementReason.values())
				.filter(reason -> reason.name().endsWith("_QUARANTINED")).toList();

		Assertions.assertThat(intake).isNotEmpty()
				.containsExactlyInAnyOrderElementsOf(QuarantineConstants.QUARANTINED_REASONS);
	}

	/**
	 * The reverse move a restore writes ends in {@code _QUARANTINE}, not
	 * {@code _QUARANTINED}: it records a file leaving, and counting it as intake
	 * would list a restored file as still quarantined.
	 */
	@Test
	void theMoveThatBringsAFileBackIsNotIntake() {
		Assertions.assertThat(QuarantineConstants.QUARANTINED_REASONS)
				.doesNotContain(MovementReason.RESTORED_FROM_QUARANTINE);
	}
}