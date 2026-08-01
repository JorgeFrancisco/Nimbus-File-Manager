package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning of the offline location rebuild over already-inventoried media.
 * Reverse geocoding is CPU-bound (Point-in-Polygon over administrative
 * polygons), so a rebuild of a large catalog resolves coordinates in parallel
 * and reuses parsed boundary geometries across the many media that fall in the
 * same region.
 */
@ConfigurationProperties(prefix = "nimbus-file-manager.location.rebuild")
public class LocationRebuildProperties {

	/**
	 * Number of media resolved concurrently. Kept modest by default so the rebuild
	 * never starves the shared database connection pool or the rest of the
	 * application; each worker holds one transaction at a time.
	 */
	private int parallelism = 4;

	/**
	 * Maximum number of parsed boundary geometries kept in memory. Bounds the
	 * geometry cache so a worldwide dataset is never fully loaded into RAM; beyond
	 * this size, overflow polygons are parsed on demand without being cached.
	 */
	private int geometryCacheMaxSize = 50_000;

	public int getParallelism() {
		return parallelism;
	}

	public void setParallelism(int parallelism) {
		this.parallelism = parallelism;
	}

	public int getGeometryCacheMaxSize() {
		return geometryCacheMaxSize;
	}

	public void setGeometryCacheMaxSize(int geometryCacheMaxSize) {
		this.geometryCacheMaxSize = geometryCacheMaxSize;
	}
}