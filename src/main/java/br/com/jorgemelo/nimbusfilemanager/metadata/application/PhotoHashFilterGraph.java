package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataConstants;

/**
 * How a photo becomes the fixed grayscale sample the perceptual hash is
 * computed from, written the two ways ffmpeg needs it: for one photo, and for a
 * group of them decoded in a single invocation.
 *
 * <p>
 * The group is the reason this is not a string built where it is used. Each
 * photo gets its own decoder and is normalized on its own branch before the
 * branches are joined, so nothing about one photo can change how the next one
 * is read. Feeding the files to a single decoder instead - which is what the
 * concat demuxer does - makes them share a decoding context, and a photo whose
 * size or pixel format differs from the one before it forces a reconfiguration
 * that silently drops its frame. The group then comes back short, and since the
 * samples are told apart only by position, the whole group has to be thrown
 * away. It was measured happening to roughly one group in eleven.
 *
 * <p>
 * The frame is also pinned to the first one per input. A file the catalog calls
 * a photo is sometimes a short clip with a photo's extension, and left alone it
 * would contribute one sample per frame - hundreds of them, from one item.
 */
public final class PhotoHashFilterGraph {

	/** What one photo goes through, as a {@code -vf} chain. */
	public static String scale() {
		return "scale=" + MetadataConstants.SAMPLE_SIDE + ":" + MetadataConstants.SAMPLE_SIDE
				+ ":flags=lanczos,format=gray";
	}

	/**
	 * The same normalization applied to each of {@code photos} inputs and then
	 * concatenated, as a {@code -filter_complex} graph whose single output pad is
	 * {@code [out]}.
	 */
	public static String forPhotos(int photos) {
		StringBuilder graph = new StringBuilder();

		for (int index = 0; index < photos; index++) {
			// The aspect ratio is pinned as well as the size, because it survives the
			// resize: a 16:9 photo and a 4:3 one both become 32x32 while keeping the
			// sample ratios they had, and concat refuses inputs whose ratios differ - it
			// compares them, not just the dimensions.
			//
			// Timestamps are rebased for a related reason: concat expects each of its
			// inputs to start at zero, and a trimmed frame keeps the timestamp it had.
			graph.append('[').append(index).append(":v]trim=end_frame=1,").append(scale())
					.append(",setsar=1,setpts=PTS-STARTPTS[v").append(index).append("];");
		}

		for (int index = 0; index < photos; index++) {
			graph.append("[v").append(index).append(']');
		}

		return graph.append("concat=n=").append(photos).append(":v=1:a=0[out]").toString();
	}

	private PhotoHashFilterGraph() {
	}
}