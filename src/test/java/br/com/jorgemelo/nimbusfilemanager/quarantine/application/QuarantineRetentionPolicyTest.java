package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;

class QuarantineRetentionPolicyTest {

	private final AppSettingService appSettingService = mock(AppSettingService.class);
	private final MovementRepository movementRepository = mock(MovementRepository.class);
	private final QuarantineRetentionPolicy policy = new QuarantineRetentionPolicy(appSettingService,
			movementRepository, Clock.systemDefaultZone());

	@Test
	void reportsTheConfiguredRetentionWindow() {
		when(appSettingService.intValue(eq(SettingsConstants.TRASH_RETENTION_DAYS), anyInt())).thenReturn(30);

		Assertions.assertThat(policy.retentionDays()).isEqualTo(30);
	}

	@Test
	void reportsNoRetentionWindowWhenTheSettingIsBlankOrInvalid() {
		// A blank/invalid setting resolves to the fallback passed in, which is
		// deliberately non-positive: no purge runs, and no deadline is promised to the
		// user on the screens that show one.
		when(appSettingService.intValue(eq(SettingsConstants.TRASH_RETENTION_DAYS), anyInt()))
				.thenAnswer(invocation -> invocation.getArgument(1));

		Assertions.assertThat(policy.retentionDays()).isZero();
	}

	/** Nothing is overdue while the window is disabled, so nothing is asked. */
	@Test
	void nothingIsOverdueWhileRetentionIsDisabled() {
		Assertions.assertThat(policy.hasOverdue(0)).isFalse();

		verify(movementRepository, never()).existsByStatusAndReasonInAndMovedAtBefore(any(), any(), any());
	}

	/**
	 * Only whether, never what: the daily pass asks this to decide if it is worth
	 * queueing at all, and loading five thousand rows to answer a yes/no would
	 * cost more than the pass it is deciding about.
	 */
	@Test
	void asksTheDatabaseWhetherAnythingIsOverdueWithoutLoadingIt() {
		when(movementRepository.existsByStatusAndReasonInAndMovedAtBefore(eq(MovementStatus.MOVED),
				eq(QuarantineConstants.QUARANTINED_REASONS), any())).thenReturn(true);

		Assertions.assertThat(policy.hasOverdue(90)).isTrue();

		verify(movementRepository, never()).findByStatusAndReasonInAndMovedAtBeforeOrderByIdAsc(any(), any(), any(),
				any());
	}
}