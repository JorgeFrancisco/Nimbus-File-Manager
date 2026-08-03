package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MediaOrientation;

@Component
public class MediaOrientationResolver {

	public Integer normalizeRotation(Integer rotation) {
		if (rotation == null) {
			return null;
		}

		int normalized = rotation % 360;

		if (normalized < 0) {
			normalized += 360;
		}

		return normalized;
	}

	public Integer exifOrientationToRotation(Integer orientationCode) {
		if (orientationCode == null) {
			return null;
		}

		return switch (orientationCode) {
		case 3, 4 -> 180;
		case 5, 6 -> 90;
		case 7, 8 -> 270;
		default -> 0;
		};
	}

	public Integer displayWidth(Integer width, Integer height, Integer rotation) {
		if (width == null || height == null) {
			return null;
		}

		return isRotated(rotation) ? height : width;
	}

	public Integer displayHeight(Integer width, Integer height, Integer rotation) {
		if (width == null || height == null) {
			return null;
		}

		return isRotated(rotation) ? width : height;
	}

	public MediaOrientation orientationType(Integer width, Integer height, Integer rotation) {
		if (width == null || height == null) {
			return MediaOrientation.UNKNOWN;
		}

		int effectiveWidth = displayWidth(width, height, rotation);
		int effectiveHeight = displayHeight(width, height, rotation);

		if (effectiveWidth > effectiveHeight) {
			return MediaOrientation.LANDSCAPE;
		}

		if (effectiveHeight > effectiveWidth) {
			return MediaOrientation.PORTRAIT;
		}

		return MediaOrientation.SQUARE;
	}

	private boolean isRotated(Integer rotation) {
		return rotation != null && normalizeRotation(rotation) % 180 != 0;
	}
}