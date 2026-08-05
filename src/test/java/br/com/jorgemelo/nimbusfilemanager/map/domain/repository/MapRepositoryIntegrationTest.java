package br.com.jorgemelo.nimbusfilemanager.map.domain.repository;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationProvider;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.MediaGeoLocation;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.ResolvedPlace;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.MediaGeoLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.map.domain.repository.projection.MapAdministrativePinProjection;
import br.com.jorgemelo.nimbusfilemanager.map.domain.repository.projection.MapExifPinProjection;
import br.com.jorgemelo.nimbusfilemanager.map.domain.repository.projection.MapMediaItemProjection;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationConfidence;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.MediaMetadata;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

/**
 * Runtime checks that {@link MapRepository}'s native aggregate queries behave
 * against a real PostgreSQL instance - they lean on Postgres-only constructs
 * ({@code COUNT(*) FILTER}, {@code FLOOR(...)} grid bucketing and
 * {@code IS NOT DISTINCT FROM}) that HSQLDB/H2 cannot reproduce. Media with
 * EXIF coordinates aggregate by a square grid cell whose size the caller
 * derives from the zoom; coordinate-less media aggregate by their resolved
 * administrative region; inactive media never appear.
 */
class MapRepositoryIntegrationTest extends SharedPostgresIntegrationTest {

	private static final Pageable PAGE = PageRequest.of(0, 50);

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private MediaGeoLocationRepository geoLocationRepository;

	@Autowired
	private MapRepository mapRepository;

	@BeforeEach
	void seed() {
		// Three EXIF media within ~11 m of each other: two photos and one video whose
		// coordinates fall in the same grid cell (at the ~111 m cell used below), so
		// they form a single pin.
		exif(FileType.PHOTO, -23.55051, -46.63334, LocalDateTime.of(2024, Month.MAY, 1, 10, 0), "Curitiba", "Paraná");
		exif(FileType.PHOTO, -23.55049, -46.63326, LocalDateTime.of(2024, Month.MAY, 2, 10, 0), null, null);
		exif(FileType.VIDEO, -23.55052, -46.63331, LocalDateTime.of(2024, Month.MAY, 3, 10, 0), null, null);

		// A distinct EXIF photo at a far-away point: its own separate pin.
		exif(FileType.PHOTO, 10.0, 20.0, LocalDateTime.of(2024, Month.JANUARY, 1, 0, 0), null, null);

		// An inactive EXIF media at the busy coordinate must be excluded everywhere.
		CatalogFile deleted = exif(FileType.PHOTO, -23.55050, -46.63330, LocalDateTime.of(2024, Month.JUNE, 1, 0, 0),
				null, null);
		deleted.setLifecycleStatus(LifecycleStatus.DELETED);
		catalogFileRepository.saveAndFlush(deleted);

		// Coordinate-less media resolved only to an administrative region.
		administrative(FileType.PHOTO, LocalDateTime.of(2023, Month.AUGUST, 1, 0, 0), "BR", "Paraná", "Londrina");
		administrative(FileType.VIDEO, LocalDateTime.of(2023, Month.AUGUST, 2, 0, 0), "BR", "Paraná", "Londrina");
		administrative(FileType.PHOTO, LocalDateTime.of(2023, Month.SEPTEMBER, 1, 0, 0), "BR", "São Paulo", null);
	}

	@Test
	void exifPinsAggregateByTheGridCellWithTypeCountsAndPlaceLabel() {
		// Cell 0.001deg (~111 m) puts the three nearby media in one grid cell; the pin
		// sits at the cell centre, so it lands within half a cell of the real points.
		List<MapExifPinProjection> pins = mapRepository.exifPins(0.001);

		MapExifPinProjection busy = pins.stream().filter(p -> p.getTotal() == 3).findFirst().orElseThrow();

		Assertions.assertThat(busy.getLat()).isCloseTo(-23.5505, Assertions.offset(0.001));
		Assertions.assertThat(busy.getLon()).isCloseTo(-46.6333, Assertions.offset(0.001));
		Assertions.assertThat(busy.getPhotos()).isEqualTo(2);
		Assertions.assertThat(busy.getVideos()).isEqualTo(1);
		Assertions.assertThat(busy.getCity()).isEqualTo("Curitiba");
		Assertions.assertThat(busy.getState()).isEqualTo("Paraná");
		Assertions.assertThat(busy.getCountry()).isEqualTo("Brasil");
		Assertions.assertThat(busy.getCoverId()).isNotNull();
		Assertions.assertThat(busy.getCoverFileType()).isEqualTo("VIDEO");
		Assertions.assertThat(busy.getCoverFileName()).endsWith(".mp4");

		Assertions.assertThat(pins).anyMatch(p -> Math.abs(p.getLat() - 10.0) < 0.001);
	}

	@Test
	void administrativePinsGroupByRegionAndCoverOnlyCoordinateLessMedia() {
		List<MapAdministrativePinProjection> pins = mapRepository.administrativePins();

		MapAdministrativePinProjection londrina = pins.stream().filter(p -> "Londrina".equals(p.getCityName()))
				.findFirst().orElseThrow();

		Assertions.assertThat(londrina.getCountryCode()).isEqualTo("BR");
		Assertions.assertThat(londrina.getStateName()).isEqualTo("Paraná");
		Assertions.assertThat(londrina.getTotal()).isEqualTo(2);
		Assertions.assertThat(londrina.getPhotos()).isEqualTo(1);
		Assertions.assertThat(londrina.getVideos()).isEqualTo(1);
		Assertions.assertThat(londrina.getCoverId()).isNotNull();
		Assertions.assertThat(londrina.getCoverFileType()).isEqualTo("VIDEO");

		Assertions.assertThat(pins).anyMatch(p -> "São Paulo".equals(p.getStateName()) && p.getCityName() == null);
	}

	@Test
	void exifPinItemsReturnTheMediaInsideTheCellRangeOrderedByCaptureDateDescending() {
		// The grid cell that holds the three nearby media: lat [-23.551, -23.550),
		// lon [-46.634, -46.633). The deleted media in the same cell must not appear.
		Page<MapMediaItemProjection> items = mapRepository.exifPinItems(-23.551, -23.550, -46.634, -46.633, PAGE);

		Assertions.assertThat(items.getTotalElements()).isEqualTo(3);
		Assertions.assertThat(items.getContent().get(0).getCaptureDate())
				.isEqualTo(LocalDateTime.of(2024, Month.MAY, 3, 10, 0));
	}

	@Test
	void exifPinsInBoundsReturnOnlyTheCoordinatesInsideTheViewport() {
		List<MapExifPinProjection> inParana = mapRepository.exifPinsInBounds(-24.0, -47.0, -23.0, -46.0, 0.001, 100);

		Assertions.assertThat(inParana).hasSize(1);
		Assertions.assertThat(inParana.get(0).getLat()).isCloseTo(-23.5505, Assertions.offset(0.001));
		Assertions.assertThat(inParana.get(0).getTotal()).isEqualTo(3);

		Assertions.assertThat(mapRepository.exifPinsInBounds(-90.0, -180.0, 90.0, 180.0, 0.001, 100)).hasSize(2);
	}

	@Test
	void exifPinsInBoundsHonourTheLimit() {
		Assertions.assertThat(mapRepository.exifPinsInBounds(-90.0, -180.0, 90.0, 180.0, 0.001, 1)).hasSize(1);
	}

	@Test
	void administrativePinItemsMatchNullCityThroughIsNotDistinctFrom() {
		Page<MapMediaItemProjection> londrina = mapRepository.administrativePinItems("BR", "Paraná", "Londrina", PAGE);
		Page<MapMediaItemProjection> saoPaulo = mapRepository.administrativePinItems("BR", "São Paulo", null, PAGE);

		Assertions.assertThat(londrina.getTotalElements()).isEqualTo(2);
		Assertions.assertThat(saoPaulo.getTotalElements()).isEqualTo(1);
	}

	private CatalogFile exif(FileType fileType, double latitude, double longitude, LocalDateTime captureDate,
			String city, String state) {
		CatalogFile file = persist(fileType, captureDate, latitude, longitude);

		if (city != null) {
			resolvePlace(file, "BR", state, city);
		}

		return file;
	}

	private void administrative(FileType fileType, LocalDateTime captureDate, String countryCode, String stateName,
			String cityName) {
		CatalogFile file = persist(fileType, captureDate, null, null);

		resolvePlace(file, countryCode, stateName, cityName);
	}

	private CatalogFile persist(FileType fileType, LocalDateTime captureDate, Double latitude, Double longitude) {
		String key = "map-it-" + System.nanoTime();
		String extension = fileType == FileType.VIDEO ? "mp4" : "jpg";
		String path = "C:/Media/" + key + "." + extension;

		CatalogFile file = CatalogFile.builder().fileKey(key).fileName(key + "." + extension).extension(extension)
				.sizeBytes(1024L).modifiedAt(LocalDateTime.now()).fileType(fileType).build();

		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(path).currentFolder("C:/Media")
				.originalPath(path).originalFolder("C:/Media").build());

		file.setMetadata(MediaMetadata.builder().catalogFile(file).category(FileCategory.MEDIA)
				.subcategory(MediaSubcategory.CAMERA).captureDate(captureDate).latitude(latitude).longitude(longitude)
				.build());

		return catalogFileRepository.saveAndFlush(file);
	}

	private void resolvePlace(CatalogFile file, String countryCode, String stateName, String cityName) {
		ResolvedPlace place = ResolvedPlace.builder().countryCode(countryCode).countryName("Brasil")
				.stateName(stateName).cityName(cityName).confidence(LocationConfidence.HIGH)
				.provider(LocationProvider.ADMIN_BOUNDARIES).resolvedAt(LocalDateTime.now()).build();

		geoLocationRepository
				.saveAndFlush(MediaGeoLocation.builder().id(file.getId()).place(place).manual(false).build());
	}
}