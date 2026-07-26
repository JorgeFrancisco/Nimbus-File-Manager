package br.com.jorgemelo.nimbusfilemanager.conversion.application.dto;

/**
 * How one ffmpeg invocation ended. {@code finished} is false when the process
 * had to be killed on timeout, which is a failure even though there is no exit
 * code to report.
 */
public record TranscodeExecution(boolean finished, int exitCode, String errorOutput) {

	public boolean successful() {
		return finished && exitCode == 0;
	}
}