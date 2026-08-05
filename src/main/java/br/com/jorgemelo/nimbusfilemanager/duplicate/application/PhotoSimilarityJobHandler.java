package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Grouping visually similar photos, off the queue.
 *
 * <p>
 * One at a time, which is what the background runner's flag already enforced.
 * The limit is per type, so a video analysis may run alongside - also what the
 * pool of two threads allowed - but never two photo analyses, which would spend
 * twice the memory to produce two answers to the same question.
 *
 * <p>
 * Resumable: it reads fingerprints and writes a result nobody can see until it
 * is published, so a second attempt from the start is indistinguishable from a
 * first. What the abandoned attempt leaves is an unpublished BUILDING row, which
 * is swept rather than resumed.
 */
@Component
public class PhotoSimilarityJobHandler implements ExecutionJobHandler {

	private final PhotoSimilarityService photoSimilarityService;
	private final SimilarityJob similarityJob;

	public PhotoSimilarityJobHandler(PhotoSimilarityService photoSimilarityService, SimilarityJob similarityJob) {
		this.photoSimilarityService = photoSimilarityService;
		this.similarityJob = similarityJob;
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.SIMILARITY_PHOTO;
	}

	/**
	 * The analysis is a query over what is already fingerprinted, not a walk over
	 * a folder. It writes its result and touches no file.
	 */
	@Override
	public boolean requiresPathLock() {
		return false;
	}

	@Override
	public boolean resumable() {
		return true;
	}

	/**
	 * No checkpoint between batches: this reads fingerprints and writes rows of
	 * its own, and no file of the user's is touched, so there is no irreversible
	 * write to guard. The ownership still travels with it, because closing the row
	 * is a write about a taking.
	 */
	@Override
	public void handle(Execution execution, ClaimedExecution claimed, ExecutionOwnership ownership) {
		similarityJob.run(photoSimilarityService, execution, claimed, ownership);
	}
}