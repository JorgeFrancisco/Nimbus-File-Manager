package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

import java.util.List;
import java.util.UUID;

/**
 * A video reassembled for comparison: its public id, the ordered frame hashes
 * (by {@code sampleIndex}) and the coarse signals used for cheap candidate
 * bucketing (duration and display dimensions). Frames are aligned across two
 * signatures by {@code sampleIndex}, so the comparison is duration-relative.
 */
public record VideoSignature(UUID id, List<VideoFrameHash> frames, Double durationSeconds, Integer width,
		Integer height) {
}