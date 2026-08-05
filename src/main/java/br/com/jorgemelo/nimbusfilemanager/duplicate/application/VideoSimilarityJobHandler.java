package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Grouping visually similar videos, off the queue.
 *
 * <p>
 * The heavier of the two: it holds every sampled frame of every candidate in
 * memory - the cap counts frame rows for that reason - and spends its time in
 * the same SSIM the photo analysis uses. One at a time, for both reasons.
 */
@Component
public class VideoSimilarityJobHandler implements ExecutionJobHandler {

	private final VideoSimilarityService videoSimilarityService;
	private final SimilarityJob similarityJob;

	public VideoSimilarityJobHandler(VideoSimilarityService videoSimilarityService, SimilarityJob similarityJob) {
		this.videoSimilarityService = videoSimilarityService;
		this.similarityJob = similarityJob;
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.SIMILARITY_VIDEO;
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

	@Override
	public void handle(Execution execution, ClaimedExecution claimed, ExecutionOwnership ownership) {
		similarityJob.run(videoSimilarityService, execution, claimed);
	}
}