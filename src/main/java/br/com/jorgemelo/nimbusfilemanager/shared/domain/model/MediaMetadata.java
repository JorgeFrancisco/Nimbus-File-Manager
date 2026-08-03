package br.com.jorgemelo.nimbusfilemanager.shared.domain.model;

import java.time.LocalDateTime;

import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MediaOrientation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "media_metadata")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class MediaMetadata {

	@Id
	@Column(name = "catalog_file_id")
	@EqualsAndHashCode.Include
	private Long id;

	@MapsId
	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "catalog_file_id")
	@ToString.Exclude
	private CatalogFile catalogFile;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private FileCategory category;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private MediaSubcategory subcategory;

	@Column(name = "year")
	private Integer year;

	@Column(name = "month")
	private Integer month;

	@Column(name = "day")
	private Integer day;

	@Column(name = "year_month")
	private String yearMonth;

	@Column(name = "capture_date")
	private LocalDateTime captureDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "date_source", length = 30)
	private DateSource dateSource;

	@Column(name = "stored_width")
	private Integer storedWidth;

	@Column(name = "stored_height")
	private Integer storedHeight;

	@Column(name = "display_width")
	private Integer displayWidth;

	@Column(name = "display_height")
	private Integer displayHeight;

	@Column(name = "orientation_code")
	private Integer orientationCode;

	@Column(name = "rotation")
	private Integer rotation;

	@Enumerated(EnumType.STRING)
	@Column(name = "orientation_type", length = 30)
	private MediaOrientation orientationType;

	@Column(name = "manufacturer")
	private String manufacturer;

	@Column(name = "model")
	private String model;

	@Column(name = "latitude")
	private Double latitude;

	@Column(name = "longitude")
	private Double longitude;

	@Column(name = "metadata_json")
	private String metadataJson;
}