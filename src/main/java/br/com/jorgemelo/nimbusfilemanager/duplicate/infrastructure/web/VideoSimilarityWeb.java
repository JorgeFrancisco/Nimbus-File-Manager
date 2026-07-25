package br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.web;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.VideoSimilarityAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.VideoSimilarityService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.VideoFingerprintBacklogAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.VideoFingerprintBacklogService;

/**
 * Parameter object bundling the video-similarity collaborators the Duplicados
 * screen needs, so {@link DuplicatesWebController}'s constructor stays within
 * the parameter limit (the video "Vídeos semelhantes" tab mirrors the photo tab
 * and would otherwise add four more constructor arguments).
 */
@Component
public class VideoSimilarityWeb {

	private final VideoSimilarityService similarityService;
	private final VideoSimilarityAsyncRunner similarityRunner;
	private final VideoFingerprintBacklogService backlogService;
	private final VideoFingerprintBacklogAsyncRunner backlogRunner;

	public VideoSimilarityWeb(VideoSimilarityService similarityService, VideoSimilarityAsyncRunner similarityRunner,
			VideoFingerprintBacklogService backlogService, VideoFingerprintBacklogAsyncRunner backlogRunner) {
		this.similarityService = similarityService;
		this.similarityRunner = similarityRunner;
		this.backlogService = backlogService;
		this.backlogRunner = backlogRunner;
	}

	public VideoSimilarityService similarityService() {
		return similarityService;
	}

	public VideoSimilarityAsyncRunner similarityRunner() {
		return similarityRunner;
	}

	public VideoFingerprintBacklogService backlogService() {
		return backlogService;
	}

	public VideoFingerprintBacklogAsyncRunner backlogRunner() {
		return backlogRunner;
	}
}