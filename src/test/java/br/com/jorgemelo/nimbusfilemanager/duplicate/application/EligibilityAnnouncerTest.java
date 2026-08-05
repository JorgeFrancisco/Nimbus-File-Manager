package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.EligibilityChanged;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * The two questions every operation that moves files has to answer before it
 * says anything: whether a move of this shape could change who may be analysed,
 * and - if so - how to say it once.
 *
 * <p>
 * The paths come from {@link TempDir} rather than from a literal with a drive
 * letter: the answer is computed over absolute, normalised paths, and on Linux
 * {@code "D:/library"} is a relative name of one segment that normalisation
 * prefixes with the runner's working directory.
 */
class EligibilityAnnouncerTest {

	private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
	private final DuplicateExclusionService duplicateExclusionService = mock(DuplicateExclusionService.class);

	private final EligibilityAnnouncer announcer = new EligibilityAnnouncer(eventPublisher,
			duplicateExclusionService);

	@Test
	void announcesTheFactAndTheOperationThatCausedIt() {
		announcer.announce("quarantine restore");

		verify(eventPublisher).publishEvent(new EligibilityChanged("quarantine restore"));
	}

	/**
	 * The common case, and the reason the question is asked at all: with nothing
	 * excluded, a folder is not something the analysis has an opinion about, so a
	 * run that moves the whole library changes nobody's eligibility.
	 */
	@Test
	void aRepointChangesNothingWhileNoFolderIsExcluded(@TempDir Path root) {
		when(duplicateExclusionService.excludedFolders()).thenReturn(List.of());

		assertThat(announcer.repointCanChangeEligibility(text(root.resolve("from")), text(root.resolve("to"))))
				.isFalse();

		verify(duplicateExclusionService, never()).isUnderExcludedFolder(any(), any());
	}

	/** Files leaving a hidden tree become visible to the comparison. */
	@Test
	void aRepointOutOfAnExcludedFolderCanChangeEligibility(@TempDir Path root) {
		excluding(root.resolve("hidden"));

		assertThat(announcer.repointCanChangeEligibility(text(root.resolve("hidden").resolve("2024")),
				text(root.resolve("visible")))).isTrue();
	}

	/** And the reverse: files arriving in one stop being compared. */
	@Test
	void aRepointIntoAnExcludedFolderCanChangeEligibility(@TempDir Path root) {
		excluding(root.resolve("hidden"));

		assertThat(announcer.repointCanChangeEligibility(text(root.resolve("visible")),
				text(root.resolve("hidden")))).isTrue();
	}

	/**
	 * A whole tree moving carries the excluded folders inside it away from the
	 * paths that named them, so what was hidden under the old name is not hidden
	 * under the new one. Asked about the tree because that is what the caller knows
	 * before it starts.
	 */
	@Test
	void movingATreeThatContainsAnExcludedFolderCanChangeEligibility(@TempDir Path root) {
		excluding(root.resolve("library").resolve("hidden"));

		assertThat(announcer.repointCanChangeEligibility(text(root.resolve("library")), text(root.resolve("moved"))))
				.isTrue();
	}

	/** Two trees the exclusion list has never heard of: nothing to say. */
	@Test
	void aRepointBetweenTwoUnrelatedTreesChangesNothing(@TempDir Path root) {
		excluding(root.resolve("hidden"));

		assertThat(announcer.repointCanChangeEligibility(text(root.resolve("one")), text(root.resolve("two"))))
				.isFalse();
	}

	/**
	 * The single-tree form, which is what a reconciliation repairing stale paths
	 * under the folder it walked is doing.
	 */
	@Test
	void aRepairInsideATreeHoldingAnExcludedFolderCanChangeEligibility(@TempDir Path root) {
		excluding(root.resolve("library").resolve("hidden"));

		assertThat(announcer.repointCanChangeEligibility(text(root.resolve("library")))).isTrue();
		assertThat(announcer.repointCanChangeEligibility(text(root.resolve("elsewhere")))).isFalse();
	}

	/**
	 * A path an operation could not name - an execution row with no target, say -
	 * is not an argument for a regroup, and must not be an exception either.
	 */
	@Test
	void anAbsentPathIsNotAReasonToAnnounceAnything(@TempDir Path root) {
		excluding(root.resolve("hidden"));

		assertThat(announcer.repointCanChangeEligibility(null, "  ")).isFalse();
	}

	private void excluding(Path folder) {
		List<String> excluded = List.of(PathUtils.normalize(folder).replace('\\', '/'));

		when(duplicateExclusionService.excludedFolders()).thenReturn(excluded);

		when(duplicateExclusionService.isUnderExcludedFolder(any(), any())).thenCallRealMethod();
	}

	private String text(Path path) {
		return PathUtils.normalize(path);
	}
}