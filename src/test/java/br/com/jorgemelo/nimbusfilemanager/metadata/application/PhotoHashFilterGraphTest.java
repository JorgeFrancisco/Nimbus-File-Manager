package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataConstants;

class PhotoHashFilterGraphTest {

	/**
	 * A branch per input is the whole point: it is what keeps one photo's size or
	 * pixel format from reconfiguring the decoding of the next one and costing it
	 * its frame.
	 */
	@Test
	void everyPhotoIsNormalizedOnABranchOfItsOwnBeforeTheBranchesAreJoined() {
		String graph = PhotoHashFilterGraph.forPhotos(3);

		assertThat(graph).contains(branch(0)).contains(branch(1)).contains(branch(2))
				.endsWith("[v0][v1][v2]concat=n=3:v=1:a=0[out]");
	}

	/**
	 * The aspect ratio survives the resize, and concat compares it: without pinning
	 * it, a group holding a widescreen photo beside a square one is refused outright
	 * - which is a whole group of work redone one photo at a time.
	 */
	@Test
	void theAspectRatioIsPinnedAndNotOnlyTheSize() {
		assertThat(PhotoHashFilterGraph.forPhotos(2).split("setsar=1", -1)).hasSize(3);
	}

	/**
	 * One frame per input, whatever the input turns out to hold. A file the catalog
	 * calls a photo is sometimes a clip wearing a photo's extension, and without
	 * this it would answer for itself hundreds of times over - which reads as a
	 * group that came back long, and costs the whole group.
	 */
	@Test
	void eachInputContributesExactlyOneFrame() {
		assertThat(PhotoHashFilterGraph.forPhotos(4).split("trim=end_frame=1", -1)).hasSize(5);
	}

	/** One branch and no concatenation to do, but the same output pad. */
	@Test
	void aGroupOfOneIsStillAGraphWithTheSameOutput() {
		assertThat(PhotoHashFilterGraph.forPhotos(1)).endsWith("[v0]concat=n=1:v=1:a=0[out]");
	}

	/** The sample the hash is computed from has one shape, and this fixes it. */
	@Test
	void theScaleIsTheFixedGrayscaleSampleTheHashExpects() {
		assertThat(PhotoHashFilterGraph.scale()).isEqualTo("scale=" + MetadataConstants.SAMPLE_SIDE + ":"
				+ MetadataConstants.SAMPLE_SIDE + ":flags=lanczos,format=gray");
	}

	private static String branch(int index) {
		return "[" + index + ":v]trim=end_frame=1," + PhotoHashFilterGraph.scale() + ",setsar=1,setpts=PTS-STARTPTS[v"
				+ index + "];";
	}
}