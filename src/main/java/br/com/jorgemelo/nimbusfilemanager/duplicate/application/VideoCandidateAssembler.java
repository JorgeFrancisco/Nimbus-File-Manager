package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoCandidate;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoFrameHash;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoSignature;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.VideoFrameRawResponse;

/**
 * Frame rows back into videos.
 *
 * <p>
 * The fingerprint query returns one row per sampled frame, ordered by file and
 * then by {@code sampleIndex}, because that is the shape the database has. The
 * comparison wants a video with its frames. Turning one into the other is a
 * small, complete job with no say in what happens next, so it lives here rather
 * than inside the analysis - which was carrying it along with the grouping, the
 * persistence and the publication.
 *
 * <p>
 * The contiguity of the rows is the whole algorithm: a change of id closes the
 * previous video. That holds because the query orders by it, and a caller that
 * shuffled the rows would get videos with frames missing rather than an error -
 * which is why the ordering is stated in the query's own documentation and not
 * only here.
 */
final class VideoCandidateAssembler {

	private VideoCandidateAssembler() {
	}

	static List<VideoCandidate> assemble(List<VideoFrameRawResponse> rows) {
		List<VideoCandidate> candidates = new ArrayList<>();

		UUID currentId = null;

		List<VideoFrameHash> frames = new ArrayList<>();

		VideoFrameRawResponse head = null;

		for (VideoFrameRawResponse row : rows) {
			if (!row.id().equals(currentId)) {
				if (head != null) {
					candidates.add(toCandidate(head, frames));
				}

				currentId = row.id();

				frames = new ArrayList<>();

				head = row;
			}

			frames.add(new VideoFrameHash(row.sampleIndex(), row.phash(), row.luminance()));
		}

		if (head != null) {
			candidates.add(toCandidate(head, frames));
		}

		return candidates;
	}

	/** The signatures alone, which is what a relation builder compares. */
	static List<VideoSignature> signatures(List<VideoCandidate> candidates) {
		return candidates.stream().map(VideoCandidate::signature).toList();
	}

	private static VideoCandidate toCandidate(VideoFrameRawResponse head, List<VideoFrameHash> frames) {
		VideoSignature signature = new VideoSignature(head.id(), List.copyOf(frames), head.durationSeconds(),
				head.width(), head.height());

		return new VideoCandidate(head.catalogFileId(), signature, head.fileName(), head.extension(),
				head.sizeBytes(), head.currentPath(), head.currentFolder(), head.modifiedAt());
	}
}