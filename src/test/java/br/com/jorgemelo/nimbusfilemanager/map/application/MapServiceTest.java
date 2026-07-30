package br.com.jorgemelo.nimbusfilemanager.map.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.WKTReader;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.LocationLabels;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.AdminBoundaryKind;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.GeoAdminBoundary;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoAdminBoundaryRepository;
import br.com.jorgemelo.nimbusfilemanager.map.application.dto.MapBounds;
import br.com.jorgemelo.nimbusfilemanager.map.application.dto.MapMediaItem;
import br.com.jorgemelo.nimbusfilemanager.map.application.dto.MapPin;
import br.com.jorgemelo.nimbusfilemanager.map.application.dto.MapPinSource;
import br.com.jorgemelo.nimbusfilemanager.map.domain.repository.MapRepository;
import br.com.jorgemelo.nimbusfilemanager.map.domain.repository.projection.MapAdministrativePinProjection;
import br.com.jorgemelo.nimbusfilemanager.map.domain.repository.projection.MapExifPinProjection;
import br.com.jorgemelo.nimbusfilemanager.map.domain.repository.projection.MapMediaItemProjection;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;

class MapServiceTest {

	private static final UUID COVER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

	private final MapRepository mapRepository = mock(MapRepository.class);
	private final GeoAdminBoundaryRepository boundaryRepository = mock(GeoAdminBoundaryRepository.class);
	private final MapService service = new MapService(mapRepository, boundaryRepository, new LocationLabels());

	@Test
	void exifMediaPinAtTheRealRoundedCoordinateWithPlaceLabelAndCounts() {
		MapExifPinProjection row = exifRow(-23.5505, -46.6333, 3, 2, 1, "Curitiba", "Paraná");

		when(mapRepository.exifPins(anyDouble())).thenReturn(List.of(row));
		when(mapRepository.administrativePins()).thenReturn(List.of());

		List<MapPin> pins = service.pins();

		Assertions.assertThat(pins).hasSize(1);

		MapPin pin = pins.get(0);

		Assertions.assertThat(pin.source()).isEqualTo(MapPinSource.EXIF);
		Assertions.assertThat(pin.latitude()).isEqualTo(-23.5505);
		Assertions.assertThat(pin.longitude()).isEqualTo(-46.6333);
		Assertions.assertThat(pin.label()).isEqualTo("Curitiba, Paraná");
		Assertions.assertThat(pin.total()).isEqualTo(3);
		Assertions.assertThat(pin.photos()).isEqualTo(2);
		Assertions.assertThat(pin.videos()).isEqualTo(1);
		Assertions.assertThat(pin.pinId()).isNotBlank();
		Assertions.assertThat(pin.coverMediaId()).isEqualTo(COVER_ID);
		Assertions.assertThat(pin.coverFileType()).isEqualTo(FileType.PHOTO);
		Assertions.assertThat(pin.coverFileName()).isEqualTo("cover.jpg");
	}

	@Test
	void administrativeMediaPinFallsBackToTheRegionRepresentativePointInsideThePolygon() throws ParseException {
		MapAdministrativePinProjection row = adminRow("BR", "Paraná", "Curitiba", 5, 4, 1);
		GeoAdminBoundary boundary = squareBoundary(-49.3, -25.5, -49.2, -25.4);

		when(mapRepository.exifPins(anyDouble())).thenReturn(List.of());
		when(mapRepository.administrativePins()).thenReturn(List.of(row));
		when(boundaryRepository.findFirstByKindAndCountryCodeIgnoreCaseAndStateNameIgnoreCaseAndNameIgnoreCase(
				AdminBoundaryKind.MUNICIPALITY, "BR", "Paraná", "Curitiba")).thenReturn(Optional.of(boundary));

		List<MapPin> pins = service.pins();

		Assertions.assertThat(pins).hasSize(1);

		MapPin pin = pins.get(0);

		Assertions.assertThat(pin.source()).isEqualTo(MapPinSource.ADMINISTRATIVE);
		Assertions.assertThat(pin.latitude()).isBetween(-25.5, -25.4);
		Assertions.assertThat(pin.longitude()).isBetween(-49.3, -49.2);
		Assertions.assertThat(pin.label()).contains("Curitiba");
		Assertions.assertThat(pin.total()).isEqualTo(5);
		Assertions.assertThat(pin.coverMediaId()).isEqualTo(COVER_ID);
		Assertions.assertThat(pin.coverFileType()).isEqualTo(FileType.VIDEO);
	}

	@Test
	void administrativeMediaWithoutAKnownBoundaryProducesNoPin() {
		MapAdministrativePinProjection row = adminRow("ZZ", "Nowhere", "Nowhere", 2, 2, 0);

		when(mapRepository.exifPins(anyDouble())).thenReturn(List.of());
		when(mapRepository.administrativePins()).thenReturn(List.of(row));
		when(boundaryRepository.findFirstByKindAndCountryCodeIgnoreCaseAndStateNameIgnoreCaseAndNameIgnoreCase(any(),
				any(), any(), any())).thenReturn(Optional.empty());
		when(boundaryRepository.findFirstByKindAndCountryCodeIgnoreCaseAndNameIgnoreCase(any(), any(), any()))
				.thenReturn(Optional.empty());
		when(boundaryRepository.findFirstByKindAndCountryCodeIgnoreCase(any(), any())).thenReturn(Optional.empty());

		Assertions.assertThat(service.pins()).isEmpty();
	}

	@Test
	void itemsDecodesTheOpaqueExifPinIdBackIntoTheGridCellRangeThatContainsIt() {
		MapExifPinProjection row = exifRow(-23.5505, -46.6333, 1, 1, 0, null, null);

		when(mapRepository.exifPins(anyDouble())).thenReturn(List.of(row));
		when(mapRepository.administrativePins()).thenReturn(List.of());
		when(mapRepository.exifPinItems(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any()))
				.thenReturn(Page.empty());

		String pinId = service.pins().get(0).pinId();

		service.items(pinId, PageRequest.of(0, 50));

		ArgumentCaptor<Double> minLat = ArgumentCaptor.forClass(Double.class);
		ArgumentCaptor<Double> maxLat = ArgumentCaptor.forClass(Double.class);
		ArgumentCaptor<Double> minLon = ArgumentCaptor.forClass(Double.class);
		ArgumentCaptor<Double> maxLon = ArgumentCaptor.forClass(Double.class);

		verify(mapRepository).exifPinItems(minLat.capture(), maxLat.capture(), minLon.capture(), maxLon.capture(),
				eq(PageRequest.of(0, 50)));

		Assertions.assertThat(-23.5505).isBetween(minLat.getValue(), maxLat.getValue());
		Assertions.assertThat(-46.6333).isBetween(minLon.getValue(), maxLon.getValue());
	}

	@Test
	void exifMediaWithoutAResolvedPlaceIsLabelledByItsCoordinate() {
		MapExifPinProjection row = exifRow(-23.5505, -46.6333, 1, 1, 0, null, null);

		when(mapRepository.exifPins(anyDouble())).thenReturn(List.of(row));
		when(mapRepository.administrativePins()).thenReturn(List.of());

		Assertions.assertThat(service.pins().get(0).label()).isEqualTo("-23.5505, -46.6333");
	}

	@Test
	void administrativePinFallsBackToTheStateBoundaryWhenTheCityIsUnknown() throws ParseException {
		MapAdministrativePinProjection row = adminRow("BR", "Paraná", "Cidade Sem Poligono", 3, 3, 0);
		GeoAdminBoundary state = squareBoundary(-54.0, -27.0, -48.0, -22.0);

		when(mapRepository.exifPins(anyDouble())).thenReturn(List.of());
		when(mapRepository.administrativePins()).thenReturn(List.of(row));
		when(boundaryRepository.findFirstByKindAndCountryCodeIgnoreCaseAndStateNameIgnoreCaseAndNameIgnoreCase(
				AdminBoundaryKind.MUNICIPALITY, "BR", "Paraná", "Cidade Sem Poligono")).thenReturn(Optional.empty());
		when(boundaryRepository.findFirstByKindAndCountryCodeIgnoreCaseAndNameIgnoreCase(AdminBoundaryKind.STATE, "BR",
				"Paraná")).thenReturn(Optional.of(state));

		MapPin pin = service.pins().get(0);

		Assertions.assertThat(pin.source()).isEqualTo(MapPinSource.ADMINISTRATIVE);
		Assertions.assertThat(pin.latitude()).isBetween(-27.0, -22.0);
	}

	@Test
	void administrativePinFallsBackToTheCountryBoundaryWhenOnlyTheCountryIsKnown() throws ParseException {
		MapAdministrativePinProjection row = adminRow("BR", null, null, 8, 5, 3);
		GeoAdminBoundary country = squareBoundary(-74.0, -34.0, -34.0, 5.0);

		when(mapRepository.exifPins(anyDouble())).thenReturn(List.of());
		when(mapRepository.administrativePins()).thenReturn(List.of(row));
		when(boundaryRepository.findFirstByKindAndCountryCodeIgnoreCase(AdminBoundaryKind.COUNTRY, "BR"))
				.thenReturn(Optional.of(country));

		MapPin pin = service.pins().get(0);

		Assertions.assertThat(pin.source()).isEqualTo(MapPinSource.ADMINISTRATIVE);
		Assertions.assertThat(pin.latitude()).isBetween(-34.0, 5.0);
	}

	@Test
	void administrativePinUsesTheBoundingBoxCentreWhenTheGeometryIsUnreadable() {
		MapAdministrativePinProjection row = adminRow("BR", "Paraná", "Curitiba", 1, 1, 0);
		GeoAdminBoundary broken = GeoAdminBoundary.builder().kind(AdminBoundaryKind.MUNICIPALITY).name("Curitiba")
				.countryCode("BR").countryName("Brasil").stateName("Paraná").minLat(-25.6).minLon(-49.4).maxLat(-25.4)
				.maxLon(-49.2).geometry(new byte[] { 1, 2, 3 }).source("TEST").datasetVersion("test").build();

		when(mapRepository.exifPins(anyDouble())).thenReturn(List.of());
		when(mapRepository.administrativePins()).thenReturn(List.of(row));
		when(boundaryRepository.findFirstByKindAndCountryCodeIgnoreCaseAndStateNameIgnoreCaseAndNameIgnoreCase(
				AdminBoundaryKind.MUNICIPALITY, "BR", "Paraná", "Curitiba")).thenReturn(Optional.of(broken));

		MapPin pin = service.pins().get(0);

		Assertions.assertThat(pin.latitude()).isEqualTo(-25.5);
		Assertions.assertThat(pin.longitude()).isEqualTo(-49.3);
	}

	@Test
	void itemsDecodesTheOpaqueAdministrativePinIdBackIntoTheRegionQuery() throws ParseException {
		MapAdministrativePinProjection row = adminRow("BR", "São Paulo", null, 2, 2, 0);
		GeoAdminBoundary state = squareBoundary(-53.0, -25.0, -44.0, -20.0);

		when(mapRepository.exifPins(anyDouble())).thenReturn(List.of());
		when(mapRepository.administrativePins()).thenReturn(List.of(row));
		when(boundaryRepository.findFirstByKindAndCountryCodeIgnoreCaseAndNameIgnoreCase(AdminBoundaryKind.STATE, "BR",
				"São Paulo")).thenReturn(Optional.of(state));
		when(mapRepository.administrativePinItems(any(), any(), any(), any())).thenReturn(Page.empty());

		String pinId = service.pins().get(0).pinId();

		service.items(pinId, PageRequest.of(0, 50));

		verify(mapRepository).administrativePinItems("BR", "São Paulo", null, PageRequest.of(0, 50));
	}

	@Test
	void itemsRejectsAMalformedPinId() {
		Pageable pageable = PageRequest.of(0, 50);

		Assertions.assertThatThrownBy(() -> service.items("!!!not-base64!!!", pageable))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void exifMediaLabelIncludesTheResolvedCountryLikeAdministrativePins() {
		MapExifPinProjection row = exifRow(-23.5505, -46.6333, 1, 1, 0, "Curitiba", "Paraná");

		when(row.getCountry()).thenReturn("Brasil");
		when(mapRepository.exifPins(anyDouble())).thenReturn(List.of(row));
		when(mapRepository.administrativePins()).thenReturn(List.of());

		Assertions.assertThat(service.pins().get(0).label()).isEqualTo("Curitiba, Paraná, Brasil");
	}

	@Test
	void exifMediaWithABlankPlaceIsLabelledByItsCoordinate() {
		MapExifPinProjection row = exifRow(-23.5505, -46.6333, 1, 1, 0, "", "");

		when(mapRepository.exifPins(anyDouble())).thenReturn(List.of(row));
		when(mapRepository.administrativePins()).thenReturn(List.of());

		Assertions.assertThat(service.pins().get(0).label()).isEqualTo("-23.5505, -46.6333");
	}

	@Test
	void aPinWithoutARepresentativeMediaExposesANullCover() {
		MapExifPinProjection row = mock(MapExifPinProjection.class);

		when(row.getLat()).thenReturn(-23.5505);
		when(row.getLon()).thenReturn(-46.6333);
		when(row.getTotal()).thenReturn(1L);
		when(mapRepository.exifPins(anyDouble())).thenReturn(List.of(row));
		when(mapRepository.administrativePins()).thenReturn(List.of());

		MapPin pin = service.pins().get(0);

		Assertions.assertThat(pin.coverMediaId()).isNull();
		Assertions.assertThat(pin.coverFileType()).isNull();
	}

	@Test
	void administrativeMediaWithoutACountryCodeProducesNoPin() {
		MapAdministrativePinProjection row = adminRow(null, null, null, 4, 4, 0);

		when(mapRepository.exifPins(anyDouble())).thenReturn(List.of());
		when(mapRepository.administrativePins()).thenReturn(List.of(row));

		Assertions.assertThat(service.pins()).isEmpty();
	}

	@Test
	void administrativeMediaWithABlankCountryCodeProducesNoPin() {
		MapAdministrativePinProjection row = adminRow("", null, null, 4, 4, 0);

		when(mapRepository.exifPins(anyDouble())).thenReturn(List.of());
		when(mapRepository.administrativePins()).thenReturn(List.of(row));

		Assertions.assertThat(service.pins()).isEmpty();
	}

	@Test
	void administrativeMediaResolvedToACityWithoutAStateStillResolvesThroughTheCountry() throws ParseException {
		MapAdministrativePinProjection row = adminRow("BR", null, "Curitiba", 2, 2, 0);
		GeoAdminBoundary country = squareBoundary(-74.0, -34.0, -34.0, 5.0);

		when(mapRepository.exifPins(anyDouble())).thenReturn(List.of());
		when(mapRepository.administrativePins()).thenReturn(List.of(row));
		when(boundaryRepository.findFirstByKindAndCountryCodeIgnoreCase(AdminBoundaryKind.COUNTRY, "BR"))
				.thenReturn(Optional.of(country));

		MapPin pin = service.pins().get(0);

		Assertions.assertThat(pin.source()).isEqualTo(MapPinSource.ADMINISTRATIVE);
		Assertions.assertThat(pin.latitude()).isBetween(-34.0, 5.0);
	}

	@Test
	void administrativePinLabelFallsBackToTheBoundaryNameWhenNoPlaceLabelCanBeBuilt() {
		MapAdministrativePinProjection row = adminRow("BR", null, null, 6, 6, 0);
		GeoAdminBoundary country = GeoAdminBoundary.builder().kind(AdminBoundaryKind.COUNTRY).name("Brasil")
				.countryCode("BR").minLat(-34.0).minLon(-74.0).maxLat(5.0).maxLon(-34.0).source("TEST")
				.datasetVersion("test").geometry(new byte[] { 9, 9 }).build();

		when(mapRepository.exifPins(anyDouble())).thenReturn(List.of());
		when(mapRepository.administrativePins()).thenReturn(List.of(row));
		when(boundaryRepository.findFirstByKindAndCountryCodeIgnoreCase(AdminBoundaryKind.COUNTRY, "BR"))
				.thenReturn(Optional.of(country));

		Assertions.assertThat(service.pins().get(0).label()).isEqualTo("Brasil");
	}

	@Test
	void administrativePinUsesTheBoundingBoxCentreForAnEmptyGeometry() throws ParseException {
		MapAdministrativePinProjection row = adminRow("BR", "Paraná", "Curitiba", 1, 1, 0);
		Geometry empty = new WKTReader(new GeometryFactory()).read("GEOMETRYCOLLECTION EMPTY");
		GeoAdminBoundary boundary = GeoAdminBoundary.builder().kind(AdminBoundaryKind.MUNICIPALITY).name("Curitiba")
				.countryCode("BR").countryName("Brasil").stateName("Paraná").minLat(-25.6).minLon(-49.4).maxLat(-25.4)
				.maxLon(-49.2).geometry(new WKBWriter().write(empty)).source("TEST").datasetVersion("test").build();

		when(mapRepository.exifPins(anyDouble())).thenReturn(List.of());
		when(mapRepository.administrativePins()).thenReturn(List.of(row));
		when(boundaryRepository.findFirstByKindAndCountryCodeIgnoreCaseAndStateNameIgnoreCaseAndNameIgnoreCase(
				AdminBoundaryKind.MUNICIPALITY, "BR", "Paraná", "Curitiba")).thenReturn(Optional.of(boundary));

		MapPin pin = service.pins().get(0);

		Assertions.assertThat(pin.latitude()).isEqualTo(-25.5);
		Assertions.assertThat(pin.longitude()).isEqualTo(-49.3);
	}

	@Test
	void itemsRejectsAnUnknownPinType() {
		String pinId = Base64.getUrlEncoder().withoutPadding().encodeToString("X".getBytes(StandardCharsets.UTF_8));
		Pageable pageable = PageRequest.of(0, 50);

		Assertions.assertThatThrownBy(() -> service.items(pinId, pageable))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void itemsMapEachProjectionRowIntoACompactMediaItem() {
		MapExifPinProjection pinRow = exifRow(-23.5505, -46.6333, 1, 1, 0, null, null);
		UUID publicId = UUID.randomUUID();
		LocalDateTime captureDate = LocalDateTime.of(2024, Month.MAY, 3, 10, 0);
		MapMediaItemProjection itemRow = mock(MapMediaItemProjection.class);

		when(itemRow.getPublicId()).thenReturn(publicId);
		when(itemRow.getFileType()).thenReturn("VIDEO");
		when(itemRow.getFileName()).thenReturn("clip.mp4");
		when(itemRow.getCaptureDate()).thenReturn(captureDate);

		when(mapRepository.exifPins(anyDouble())).thenReturn(List.of(pinRow));
		when(mapRepository.administrativePins()).thenReturn(List.of());
		when(mapRepository.exifPinItems(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any()))
				.thenReturn(new PageImpl<>(List.of(itemRow), PageRequest.of(0, 50), 1));

		String pinId = service.pins().get(0).pinId();
		MapMediaItem item = service.items(pinId, PageRequest.of(0, 50)).getContent().get(0);

		Assertions.assertThat(item.mediaId()).isEqualTo(publicId);
		Assertions.assertThat(item.fileType()).isEqualTo(FileType.VIDEO);
		Assertions.assertThat(item.fileName()).isEqualTo("clip.mp4");
		Assertions.assertThat(item.captureDate()).isEqualTo(captureDate);
	}

	@Test
	void boundedPinsQueryTheViewportAndKeepAdministrativePinsInsideIt() throws ParseException {
		MapExifPinProjection exif = exifRow(-25.45, -49.25, 2, 2, 0, "Curitiba", "Paraná");
		MapAdministrativePinProjection admin = adminRow("BR", "Paraná", "Curitiba", 3, 3, 0);
		GeoAdminBoundary boundary = squareBoundary(-49.3, -25.5, -49.2, -25.4);
		MapBounds bounds = new MapBounds(-25.5, -49.3, -25.4, -49.2);

		when(mapRepository.exifPinsInBounds(eq(-25.5), eq(-49.3), eq(-25.4), eq(-49.2), anyDouble(), eq(100)))
				.thenReturn(List.of(exif));
		when(mapRepository.administrativePins()).thenReturn(List.of(admin));
		when(boundaryRepository.findFirstByKindAndCountryCodeIgnoreCaseAndStateNameIgnoreCaseAndNameIgnoreCase(
				AdminBoundaryKind.MUNICIPALITY, "BR", "Paraná", "Curitiba")).thenReturn(Optional.of(boundary));

		Assertions.assertThat(service.pins(bounds, 100, 12)).extracting(MapPin::source)
				.containsExactlyInAnyOrder(MapPinSource.EXIF, MapPinSource.ADMINISTRATIVE);
	}

	@Test
	void boundedPinsDropAdministrativePinsWhoseRegionFallsOutsideTheViewport() throws ParseException {
		MapAdministrativePinProjection admin = adminRow("BR", "Paraná", "Curitiba", 3, 3, 0);
		GeoAdminBoundary boundary = squareBoundary(-49.3, -25.5, -49.2, -25.4);
		MapBounds farAway = new MapBounds(10.0, 10.0, 20.0, 20.0);

		when(mapRepository.exifPinsInBounds(eq(10.0), eq(10.0), eq(20.0), eq(20.0), anyDouble(), eq(100)))
				.thenReturn(List.of());
		when(mapRepository.administrativePins()).thenReturn(List.of(admin));
		when(boundaryRepository.findFirstByKindAndCountryCodeIgnoreCaseAndStateNameIgnoreCaseAndNameIgnoreCase(
				AdminBoundaryKind.MUNICIPALITY, "BR", "Paraná", "Curitiba")).thenReturn(Optional.of(boundary));

		Assertions.assertThat(service.pins(farAway, 100, 12)).isEmpty();
	}

	@Test
	void boundedPinsExcludeAnAdministrativePinOutsideAnyViewportEdge() throws ParseException {
		MapAdministrativePinProjection admin = adminRow("BR", "Paraná", "Curitiba", 3, 3, 0);
		GeoAdminBoundary boundary = squareBoundary(-49.3, -25.5, -49.2, -25.4);

		when(mapRepository.administrativePins()).thenReturn(List.of(admin));
		when(boundaryRepository.findFirstByKindAndCountryCodeIgnoreCaseAndStateNameIgnoreCaseAndNameIgnoreCase(
				AdminBoundaryKind.MUNICIPALITY, "BR", "Paraná", "Curitiba")).thenReturn(Optional.of(boundary));
		when(mapRepository.exifPinsInBounds(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
				.thenReturn(List.of());

		// The region's representative point is ~(-25.45, -49.25); each box excludes it
		// on a different edge (south, north, west, east), covering every branch of the
		// bounds check.
		Assertions.assertThat(service.pins(new MapBounds(-25.4, -49.3, -25.0, -49.2), 100, 12)).isEmpty();
		Assertions.assertThat(service.pins(new MapBounds(-26.0, -49.3, -25.5, -49.2), 100, 12)).isEmpty();
		Assertions.assertThat(service.pins(new MapBounds(-26.0, -49.2, -25.0, -49.0), 100, 12)).isEmpty();
		Assertions.assertThat(service.pins(new MapBounds(-26.0, -49.4, -25.0, -49.3), 100, 12)).isEmpty();
	}

	private MapExifPinProjection exifRow(double lat, double lon, long total, long photos, long videos, String city,
			String state) {
		MapExifPinProjection row = mock(MapExifPinProjection.class);

		when(row.getLat()).thenReturn(lat);
		when(row.getLon()).thenReturn(lon);
		when(row.getTotal()).thenReturn(total);
		when(row.getPhotos()).thenReturn(photos);
		when(row.getVideos()).thenReturn(videos);
		when(row.getCity()).thenReturn(city);
		when(row.getState()).thenReturn(state);
		when(row.getCoverId()).thenReturn(COVER_ID);
		when(row.getCoverFileType()).thenReturn("PHOTO");
		when(row.getCoverFileName()).thenReturn("cover.jpg");

		return row;
	}

	/**
	 * A cell whose media all resolved to open water gets the wording, not the raw
	 * coordinates it used to fall back to - the pin still sits at the real spot.
	 */
	@Test
	void exifPinOfOpenWaterIsLabelledInsteadOfShowingCoordinates() {
		MapExifPinProjection row = exifRow(-24.0, -42.8, 2, 2, 0, null, null);

		when(row.getOpenSea()).thenReturn(true);
		when(mapRepository.exifPins(anyDouble())).thenReturn(List.of(row));
		when(mapRepository.administrativePins()).thenReturn(List.of());

		List<MapPin> pins = service.pins();

		Assertions.assertThat(pins).hasSize(1);
		Assertions.assertThat(pins.get(0).label()).isEqualTo("Alto-mar");
		Assertions.assertThat(pins.get(0).latitude()).isEqualTo(-24.0);
	}

	private MapAdministrativePinProjection adminRow(String countryCode, String stateName, String cityName, long total,
			long photos, long videos) {
		MapAdministrativePinProjection row = mock(MapAdministrativePinProjection.class);

		when(row.getCountryCode()).thenReturn(countryCode);
		when(row.getStateName()).thenReturn(stateName);
		when(row.getCityName()).thenReturn(cityName);
		when(row.getTotal()).thenReturn(total);
		when(row.getPhotos()).thenReturn(photos);
		when(row.getVideos()).thenReturn(videos);
		when(row.getCoverId()).thenReturn(COVER_ID);
		when(row.getCoverFileType()).thenReturn("VIDEO");
		when(row.getCoverFileName()).thenReturn("clip.mp4");

		return row;
	}

	private GeoAdminBoundary squareBoundary(double minLon, double minLat, double maxLon, double maxLat)
			throws ParseException {
		Geometry geometry = new WKTReader(new GeometryFactory())
				.read(String.format(Locale.ROOT, "POLYGON((%f %f, %f %f, %f %f, %f %f, %f %f))", minLon, minLat, maxLon,
						minLat, maxLon, maxLat, minLon, maxLat, minLon, minLat));

		return GeoAdminBoundary.builder().kind(AdminBoundaryKind.MUNICIPALITY).name("Curitiba").countryCode("BR")
				.countryName("Brasil").stateName("Paraná").minLat(minLat).minLon(minLon).maxLat(maxLat).maxLon(maxLon)
				.geometry(new WKBWriter().write(geometry)).source("TEST").datasetVersion("test").build();
	}
}