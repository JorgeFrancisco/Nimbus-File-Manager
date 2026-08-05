package br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.web;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.VideoFingerprintBacklogService;

/**
 * Parameter object bundling the video-fingerprint collaborators the Duplicados
 * screen needs, so {@link DuplicatesWebController}'s constructor stays within
 * the parameter limit.
 *
 * <p>
 * It used to carry the video similarity service, its background runner and the
 * fingerprint backlog runner. All three are gone: the screen reaches neither the
 * analysis nor the drain any more - it reads what was published and asks the
 * queue for the rest - and what is left here is the backlog's own read side.
 */
@Component
public class VideoSimilarityWeb {

	private final VideoFingerprintBacklogService backlogService;

	public VideoSimilarityWeb(VideoFingerprintBacklogService backlogService) {
		this.backlogService = backlogService;
	}

	public VideoFingerprintBacklogService backlogService() {
		return backlogService;
	}
}