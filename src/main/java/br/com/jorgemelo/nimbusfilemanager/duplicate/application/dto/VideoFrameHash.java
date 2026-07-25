package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

/**
 * One frame of a video's comparison signature: its relative position
 * ({@code sampleIndex}, the logical identity used to align two videos), the
 * 256-bit pHash and the 32x32 luminance sample for SSIM.
 */
public record VideoFrameHash(int sampleIndex, byte[] phash, byte[] luminance) {
}