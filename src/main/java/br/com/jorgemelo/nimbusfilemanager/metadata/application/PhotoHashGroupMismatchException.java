package br.com.jorgemelo.nimbusfilemanager.metadata.application;

/**
 * A group of photos decoded in one ffmpeg invocation came back with a number of
 * samples other than the number of photos asked for.
 *
 * <p>
 * It carries no file, because there is none to name: the samples are told apart
 * by position alone, so one missing sample shifts every photo after it onto the
 * wrong bytes and there is no way to tell from the stream which one was
 * dropped. The whole group is therefore unusable - not the group minus one -
 * and the only honest recovery is to decode its photos one at a time, where
 * each answer is attributable again.
 */
public class PhotoHashGroupMismatchException extends IllegalStateException {

	private static final long serialVersionUID = 1L;

	public PhotoHashGroupMismatchException(String message) {
		super(message);
	}
}