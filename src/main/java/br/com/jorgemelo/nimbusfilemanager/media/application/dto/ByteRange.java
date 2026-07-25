package br.com.jorgemelo.nimbusfilemanager.media.application.dto;

public record ByteRange(long start, long end, boolean partial) {

	public long length() {
		return end - start + 1;
	}
}