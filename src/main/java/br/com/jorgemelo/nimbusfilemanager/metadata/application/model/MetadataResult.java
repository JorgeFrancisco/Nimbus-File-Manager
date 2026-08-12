package br.com.jorgemelo.nimbusfilemanager.metadata.application.model;

import java.time.Instant;
import java.time.LocalDateTime;

import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MediaOrientation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder(toBuilder = true)
public class MetadataResult {

	private String fileName;
	private String extension;
	private Long sizeBytes;

	private String sha256;
	private String mimeType;
	private FileType fileType;

	private Instant createdAt;
	private Instant modifiedAt;

	private LocalDateTime captureDate;
	private DateSource dateSource;

	private MediaSubcategory subcategory;

	private Integer storedWidth;
	private Integer storedHeight;

	private Integer displayWidth;
	private Integer displayHeight;

	private Integer orientationCode;
	private Integer rotation;
	private MediaOrientation orientationType;

	private String manufacturer;
	private String model;

	private Double latitude;
	private Double longitude;

	private Integer iso;
	private String flash;
	private String exposureTime;
	private String fNumber;
	private String focalLength;
	private String lensModel;
	private String whiteBalance;
	private String exposureMode;
	private String exposureProgram;
	private String meteringMode;

	private LocalDateTime exifDate;

	private String container;
	private String videoCodec;
	private String audioCodec;
	private String videoProfile;

	private Double fps;
	private Long videoBitrate;
	private Long totalBitrate;

	private Double durationSeconds;

	private Boolean hdr;

	private String pixelFormat;
	private String colorSpace;
	private String colorTransfer;
	private String colorPrimaries;
	private Integer bitDepth;

	private Integer audioSampleRate;
	private Integer audioChannels;
	private String audioChannelLayout;

	private String metadataJson;
	private String exifJson;
	private String mediaInfoJson;
}