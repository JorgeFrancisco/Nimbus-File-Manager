package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.EligibilityChanged;

/**
 * The one consumer of the event, and the one decision it makes: turn "who may
 * be analysed changed" into a request to regroup, and never turn a failure to
 * make that request into a failure of the change itself.
 *
 * <p>
 * The transactional half of its contract - {@code AFTER_COMMIT} so a rolled
 * back operation asks for nothing, {@code REQUIRES_NEW} so the request owns a
 * transaction instead of joining the finished one - is not assertable here and
 * is proved where it can be, against a real database and a real worker, in
 * {@code SimilarityExecutionEndToEndIntegrationTest}.
 */
class SimilarityEligibilityRefreshTest {

	private final SimilarityLauncher similarityLauncher = mock(SimilarityLauncher.class);

	private final SimilarityEligibilityRefresh refresh = new SimilarityEligibilityRefresh(similarityLauncher);

	@Test
	void asksForEveryAnalysedFamilyToBeBroughtUpToDate() {
		refresh.onEligibilityChanged(new EligibilityChanged("file exclusion"));

		verify(similarityLauncher).refreshAfterEligibilityChange();
	}

	/**
	 * The mutation is committed and is not in question; what failed is asking for
	 * the answer to be refreshed. Letting it escape would report a quarantine that
	 * did happen as one that did not - and the publisher is a batch that has
	 * already moved the files.
	 */
	@Test
	void aFailureToAskIsNotAFailureOfWhatAlreadyHappened() {
		doThrow(new IllegalStateException("no transaction is in progress")).when(similarityLauncher)
				.refreshAfterEligibilityChange();

		assertThatCode(() -> refresh.onEligibilityChanged(new EligibilityChanged("quarantine intake")))
				.doesNotThrowAnyException();
	}
}