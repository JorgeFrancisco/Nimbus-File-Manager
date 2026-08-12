package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKBWriter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.GeoDatasetProgress;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.Feature;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.GeoDatasetIdentity;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LeveledBoundaryFile;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.AdminBoundaryKind;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.GeoDatasetState;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoDatasetStateRepository;
import br.com.jorgemelo.nimbusfilemanager.geolocation.infrastructure.persistence.GeoAdminBoundaryImportRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Streams the per-level geoBoundaries GeoJSON files and bulk-loads them into
 * geo_admin_boundary as WKB, one canonical {@link AdminBoundaryKind} per file.
 * geoBoundaries' alpha-3 {@code shapeGroup} is converted to the stored alpha-2
 * country code and the pt-BR country name is denormalized so resolution needs
 * no join. Uses JDBC batches: a worldwide dataset must not build a JPA session.
 */
@Slf4j
@Component
public class GeoJsonBoundaryImporter {

	private static final int BATCH_SIZE = 500;

	private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}]");

	private final GeoAdminBoundaryImportRepository boundaryRepository;
	private final GeoDatasetStateRepository geoDatasetStateRepository;
	private final GeoJsonBoundaryReader reader;
	private final GeoDatasetProgress progress;
	private final Clock clock;

	public GeoJsonBoundaryImporter(GeoAdminBoundaryImportRepository boundaryRepository,
			GeoDatasetStateRepository geoDatasetStateRepository, GeoJsonBoundaryReader reader,
			GeoDatasetProgress progress, Clock clock) {
		this.boundaryRepository = boundaryRepository;
		this.geoDatasetStateRepository = geoDatasetStateRepository;
		this.reader = reader;
		this.progress = progress;
		this.clock = clock;
	}

	/**
	 * Replaces the whole geo_admin_boundary content with the given per-level files.
	 * Runs in one transaction: either the new dataset is fully imported or the
	 * previous one stays untouched.
	 *
	 * <p>
	 * <b>And says what it imported, in the same transaction.</b> The row describing
	 * the installation - its version, its provenance, the moment it arrived - is
	 * written here rather than by the caller afterwards, because a fact about these
	 * rows written outside the transaction that creates them can disagree with
	 * them, and did: the previous model kept it in a file beside the downloads,
	 * where a run that imported and then failed left a database and a description
	 * of two different datasets. It is marked incomplete, because at this point it
	 * is: the territories and the publication still have to happen.
	 */
	@Transactional
	public long importDataset(List<LeveledBoundaryFile> files, GeoDatasetIdentity identity) {
		boundaryRepository.deleteAll();

		String source = identity.source();
		String datasetVersion = identity.version();

		long total = 0;

		// Walked by level rather than by file, so the three import stages happen in
		// the same order every run and a level that never arrived still occupies its
		// place in the sequence instead of shortening it.
		for (AdminBoundaryKind kind : AdminBoundaryKind.values()) {
			Path file = fileOf(files, kind);

			if (file == null) {
				progress.nothingToImport(kind);
			} else {
				total += importLevel(kind, file, source, datasetVersion);
			}

			progress.stageFinished();
		}

		if (total == 0) {
			throw new IllegalStateException("Boundary files have no importable polygons");
		}

		geoDatasetStateRepository.save(GeoDatasetState.builder().id(GeoDatasetState.SINGLETON_ID)
				.datasetVersion(datasetVersion).source(source).provider(identity.provider())
				.license(identity.license()).importedAt(LocalDateTime.now(clock)).complete(false).build());

		log.info("Imported {} boundaries (dataset version {})", total, datasetVersion);

		return total;
	}

	/**
	 * Adds supplemental files (territory polygons filling gaps in the main dataset)
	 * without touching rows already imported. Separate transaction: a failure here
	 * never rolls back the main dataset.
	 */
	@Transactional
	public long importExtra(List<LeveledBoundaryFile> files, String source, String datasetVersion) {
		long total = 0;

		for (LeveledBoundaryFile file : files) {
			total += importLevel(file.kind(), file.file(), source, datasetVersion);
		}

		return total;
	}

	/** The file this run acquired for the level, or null when it has none. */
	private static Path fileOf(List<LeveledBoundaryFile> files, AdminBoundaryKind kind) {
		return files.stream().filter(file -> file.kind() == kind).map(LeveledBoundaryFile::file).findFirst()
				.orElse(null);
	}

	private long importLevel(AdminBoundaryKind kind, Path file, String source, String datasetVersion) {
		WKBWriter writer = new WKBWriter();

		// Progress percentage comes from bytes of the file consumed by the
		// parser (the total feature count is unknown without a full pre-scan).
		progress.importing(kind, fileSize(file));

		List<SqlParameterSource> batch = new ArrayList<>(BATCH_SIZE);

		long[] imported = { 0 };

		reader.read(file, feature -> {
			SqlParameterSource row = toRow(kind, feature, writer, source, datasetVersion);

			if (row != null) {
				batch.add(row);
				// Counted as each feature is parsed, not per flushed batch: ADM0
				// has few but huge polygons, and a per-batch counter would sit at
				// zero for the whole level, looking stuck on the admin screen.
				progress.addImportedRecords(1);

				if (batch.size() == BATCH_SIZE) {
					boundaryRepository.batchInsert(batch);

					imported[0] += batch.size();

					batch.clear();
				}
			}
		}, progress::addImportedBytes);

		if (!batch.isEmpty()) {
			boundaryRepository.batchInsert(batch);

			imported[0] += batch.size();
		}

		log.info("Imported {} {} boundaries", imported[0], kind);

		return imported[0];
	}

	private long fileSize(Path file) {
		try {
			return Files.size(file);
		} catch (IOException _) {
			return -1;
		}
	}

	private SqlParameterSource toRow(AdminBoundaryKind kind, Feature feature, WKBWriter writer, String source,
			String datasetVersion) {
		String countryCode = CountryCodes.alpha2(feature.alpha3());

		if (countryCode == null) {
			return null;
		}

		String name = truncate(repairMojibake(feature.name()), 200);

		if (name == null || name.isBlank()) {
			return null;
		}

		Geometry geometry = feature.geometry();

		Envelope envelope = geometry.getEnvelopeInternal();

		// Clamp the bounding box to valid geographic ranges: floating-point
		// rounding can push an envelope slightly past the limits (e.g.
		// 180.0000000000001). The stored polygon (WKB) is untouched; only the
		// prefilter box is normalized.
		double minLat = Math.max(-90.0, envelope.getMinY());
		double maxLat = Math.min(90.0, envelope.getMaxY());
		double minLon = Math.max(-180.0, envelope.getMinX());
		double maxLon = Math.min(180.0, envelope.getMaxX());

		return new MapSqlParameterSource().addValue("kind", kind.name()).addValue("name", name)
				.addValue("countryCode", countryCode).addValue("countryName", CountryCodes.displayName(countryCode))
				.addValue("stateName", null).addValue("minLat", minLat).addValue("minLon", minLon)
				.addValue("maxLat", maxLat).addValue("maxLon", maxLon).addValue("geometry", writer.write(geometry))
				.addValue("source", source).addValue("datasetVersion", datasetVersion);
	}

	/**
	 * Repairs "double-encoded" UTF-8 (mojibake) that some geoBoundaries country
	 * files ship with - e.g. "RegiÃ³n" instead of "Región". The bytes of such a
	 * string, re-read as UTF-8, yield the correct text. The heuristic is safe: it
	 * only touches strings that carry the tell-tale 0xC2/0xC3 lead characters and
	 * whose re-decode is valid UTF-8, so correct names like "Ângulo" (where the
	 * re-decode would be invalid) are left untouched.
	 */
	private String repairMojibake(String value) {
		if (value == null || value.isEmpty()) {
			return value;
		}

		boolean suspect = false;

		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);

			if (c > 0x00FF) {
				return value; // real multi-byte characters: not this kind of mojibake
			}

			// U+00C2 / U+00C3 are the tell-tale lead chars a double-encoded UTF-8
			// string surfaces as. Escaped to keep the source ASCII-only.
			if (c == '\u00C2' || c == '\u00C3') {
				suspect = true;
			}
		}

		if (!suspect) {
			return value;
		}

		String repaired = new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);

		return repaired.indexOf('\uFFFD') >= 0 ? value : repaired;
	}

	private String truncate(String value, int maxLength) {
		if (value == null) {
			return null;
		}

		String cleaned = CONTROL_CHARS.matcher(value.strip()).replaceAll("");

		return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
	}
}