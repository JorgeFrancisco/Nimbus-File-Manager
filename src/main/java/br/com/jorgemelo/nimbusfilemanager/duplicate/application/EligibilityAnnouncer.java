package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.EligibilityChanged;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * What an operation calls, once, when what it just did changed who a duplicate
 * analysis is allowed to look at.
 *
 * <p>
 * The operations that can do that are spread over quarantine, the file
 * explorer, organization, reconciliation, the inventory and the library switch,
 * and every one of them would otherwise repeat the same two lines: build the
 * event, publish it. Having one collaborator instead is what makes "who
 * announces this, and under what condition" a question with one place to look -
 * and what keeps the condition out of nine constructors.
 *
 * <p>
 * <b>Announced at the boundary of the operation, never per file.</b> A
 * quarantine batch moves thousands of files and announces once; an organization
 * run moves a hundred thousand and announces once, or - when none of them
 * crossed the line the exclusion list draws - not at all. The caller keeps a
 * local flag and calls this at the end, which is why nothing here accumulates:
 * the accumulator belongs to the run, not to a singleton two runs would share.
 *
 * <p>
 * {@link DuplicateExclusionService} does not use this and publishes directly,
 * for the mundane reason that this holds it - editing the exclusion list is the
 * one change to eligibility whose publisher is the thing being asked about.
 */
@Service
public class EligibilityAnnouncer {

	private final ApplicationEventPublisher eventPublisher;
	private final DuplicateExclusionService duplicateExclusionService;

	public EligibilityAnnouncer(ApplicationEventPublisher eventPublisher,
			DuplicateExclusionService duplicateExclusionService) {
		this.eventPublisher = eventPublisher;
		this.duplicateExclusionService = duplicateExclusionService;
	}

	/**
	 * Says that the set changed, naming the operation that changed it.
	 *
	 * @param origin what happened, in words, and the only thing the failure path
	 * has to explain itself with. It is not read by any decision
	 */
	public void announce(String origin) {
		eventPublisher.publishEvent(new EligibilityChanged(origin));
	}

	/**
	 * Whether moving files between these two places can change who may be
	 * analysed - the question every repoint has to ask before announcing anything.
	 *
	 * <p>
	 * A move changes one input of eligibility and one only: the folder a file
	 * sits in, which matters because a folder can be excluded. So with no folder
	 * excluded at all, no move of any size changes anything, and an organization
	 * run over a hundred thousand files announces nothing - which is the answer
	 * this returns first, before reading a single path.
	 *
	 * <p>
	 * With exclusions configured it is answered about the two trees rather than
	 * about each file, because the caller knows the trees before it starts and the
	 * files only as it goes. A tree "touches" the list when it lies under an
	 * excluded folder - everything moving out of it stops being hidden - or when an
	 * excluded folder lies under it, since what was hidden inside it is about to be
	 * somewhere else. Neither true of either tree means no file the run touches can
	 * change sides.
	 *
	 * <p>
	 * It errs towards yes, and the direction is deliberate: a yes that was not
	 * needed costs one regroup over relations that are all still valid, while a no
	 * that was wrong leaves the published grouping quietly wrong until somebody
	 * asks for a rebuild.
	 */
	public boolean repointCanChangeEligibility(String fromPath, String toPath) {
		List<String> excludedFolders = duplicateExclusionService.excludedFolders();

		if (excludedFolders.isEmpty()) {
			return false;
		}

		return touches(fromPath, excludedFolders) || touches(toPath, excludedFolders);
	}

	/**
	 * The same question about repoints that stay inside one tree - a
	 * reconciliation correcting stale paths under the folder it walked, rather than
	 * a move from one place to another.
	 */
	public boolean repointCanChangeEligibility(String path) {
		return repointCanChangeEligibility(path, path);
	}

	private boolean touches(String path, List<String> excludedFolders) {
		if (path == null || path.isBlank()) {
			return false;
		}

		String normalized = PathUtils.normalize(path).replace('\\', '/');

		return duplicateExclusionService.isUnderExcludedFolder(normalized, excludedFolders)
				|| excludedFolders.stream().anyMatch(excluded -> excluded.startsWith(normalized + "/"));
	}
}