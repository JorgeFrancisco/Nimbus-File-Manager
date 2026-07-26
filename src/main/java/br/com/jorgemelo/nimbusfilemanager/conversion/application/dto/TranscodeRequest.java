package br.com.jorgemelo.nimbusfilemanager.conversion.application.dto;

import java.nio.file.Path;

/**
 * One file to bring into the H.265/MP4 shape.
 *
 * @param sourceDurationSeconds from the catalog, used twice: to turn ffmpeg's
 *                              progress into a percentage, and to prove
 *                              afterwards that the converted file is not
 *                              truncated. Null for a video whose duration was
 *                              never extracted
 * @param sourceIsHevc          the video stream is already H.265, so only the
 *                              container changes and the video is remuxed
 *                              instead of re-encoded
 */
public record TranscodeRequest(Path source, Double sourceDurationSeconds, ConversionOptions options,
		boolean sourceIsHevc) {
}