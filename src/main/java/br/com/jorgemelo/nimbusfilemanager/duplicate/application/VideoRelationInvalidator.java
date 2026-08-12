package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoComparisonInputs;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence.SimilarityRelationWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import lombok.extern.slf4j.Slf4j;

/**
 * Forgets a video's relations when the comparison would now answer differently
 * about it - and only then.
 *
 * <p>
 * A video's approved relations are computed from its frames <em>and</em> from
 * its duration and display size, and the second pair does not live in the
 * fingerprint. A re-scan that finds the file re-encoded, or a metadata rebuild
 * asked to refresh dimensions, rewrites those columns in place: same catalog id,
 * same fingerprint, different inputs to the gates that decided which pairs were
 * ever compared. Nothing in the fingerprint records that, so without this the
 * relations would go on being read as facts about a file that no longer matches
 * them, and the coverage would go on entitling every incremental run to skip it.
 *
 * <p>
 * <b>Only when the value really moved.</b> A metadata rebuild rewrites every
 * candidate it visits, and almost all of them come back identical. Forgetting
 * unconditionally would drop the coverage of a whole library on every pass, which
 * turns the incremental product back into a full one - the exact cost the
 * relations exist to avoid. So the caller hands over what the file held before
 * and what it holds now, and nothing happens unless they differ.
 *
 * <p>
 * <b>Only the video algorithm.</b> Forgetting by catalog id alone would take the
 * photo relations of the same row with it, and a still exported beside a clip
 * shares nothing with it but an id. The scope is the algorithm, which is what
 * {@link SimilarityRelationWriter#forget} takes.
 *
 * <p>
 * And it is the algorithm <em>in use</em>, asked of the bean rather than named
 * here. Naming it cost exactly what naming it always costs: when the video
 * fingerprint moved to reaching its frames by seeking - a new identifier, as any
 * change of pipeline must be - this went on forgetting relations of the previous
 * one, which nothing produces any more, and left the current ones in place. A
 * video whose duration or shape had changed would have kept being compared using
 * what was measured before it changed.
 */
@Slf4j
@Service
public class VideoRelationInvalidator {

	private final SimilarityRelationWriter similarityRelationWriter;
	private final VideoSimilarityAlgorithm algorithm;

	public VideoRelationInvalidator(SimilarityRelationWriter similarityRelationWriter,
			VideoSimilarityAlgorithm algorithm) {
		this.similarityRelationWriter = similarityRelationWriter;
		this.algorithm = algorithm;
	}

	/**
	 * @param before what the file held before the write, captured by the caller
	 * while it still could
	 * @return whether anything was forgotten, which is what a caller counts
	 */
	public boolean invalidateIfChanged(CatalogFile catalogFile, VideoComparisonInputs before) {
		if (catalogFile == null || catalogFile.getId() == null) {
			return false;
		}

		VideoComparisonInputs after = VideoComparisonInputs.of(catalogFile);

		if (before.equals(after)) {
			return false;
		}

		int forgotten = similarityRelationWriter.forget(algorithm.algorithm(), catalogFile.getId());

		log.info("Video {} changed the inputs its similarity was decided by ({} -> {}); {} relation(s) and its"
				+ " coverage were forgotten, so the next incremental run compares it again", catalogFile.getId(),
				before, after, forgotten);

		return true;
	}
}