package br.com.jorgemelo.nimbusfilemanager.metadata.application.dto;

import java.time.LocalDateTime;

import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MediaOrientation;

public record VideoMetadata(

		String container,

		String videoCodec, String audioCodec, String videoProfile,

		Integer width, Integer height, Double fps,

		Long videoBitrate, Long totalBitrate,

		Double durationSeconds, Integer rotation,

		Boolean hdr,

		String pixelFormat, String colorSpace, String colorTransfer, String colorPrimaries, Integer bitDepth,

		Integer audioSampleRate, Integer audioChannels, String audioChannelLayout,

		LocalDateTime captureDate,

		Double latitude, Double longitude,

		String mediaInfoJson,

		MediaOrientation orientationType) {
}