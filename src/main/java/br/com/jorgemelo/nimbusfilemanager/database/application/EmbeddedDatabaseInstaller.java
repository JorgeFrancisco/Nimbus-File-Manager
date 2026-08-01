package br.com.jorgemelo.nimbusfilemanager.database.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import lombok.extern.slf4j.Slf4j;

/**
 * Unpacks the PostgreSQL binaries into the folder the application looks in.
 *
 * <p>
 * The published archive holds the whole distribution under a single top folder,
 * and most of it is for building against PostgreSQL rather than running it.
 * Only three folders are kept: {@code bin} (the executables), {@code lib} (what
 * they link against) and {@code share} (the templates {@code initdb} copies
 * into a new cluster - without it, creating a cluster fails in a way that reads
 * like a permission problem). Headers, symbols and documentation are left
 * behind, which is most of the download.
 */
@Slf4j
public class EmbeddedDatabaseInstaller {

	private static final List<String> WANTED = List.of("bin", "lib", "share");

	private final ClusterLayout layout;
	private final PostgresArchiveSource archiveSource;

	public EmbeddedDatabaseInstaller(ClusterLayout layout, PostgresArchiveSource archiveSource) {
		this.layout = layout;
		this.archiveSource = archiveSource;
	}

	/**
	 * Downloads and unpacks the server.
	 *
	 * @return whether the binaries are usable once this returns
	 */
	public boolean install() {
		Path target = layout.serverFolder();

		Path archive = null;

		try {
			Files.createDirectories(target);

			archive = archiveSource.download(target);

			if (archive == null) {
				return false;
			}

			extract(archive, target);

			return layout.binariesPresent();
		} catch (IOException exception) {
			log.error("Could not install the embedded PostgreSQL into {}", target, exception);

			return false;
		} finally {
			deleteQuietly(archive);
		}
	}

	private void extract(Path archive, Path target) throws IOException {
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			var entries = zip.entries();

			while (entries.hasMoreElements()) {
				copyWhenWanted(zip, entries.nextElement(), target);
			}
		}
	}

	/**
	 * The path an entry should take under the install folder, or {@code null} when
	 * it is not one of the three folders being kept.
	 *
	 * <p>
	 * The leading folder of the archive is dropped, so the result is the same
	 * whichever name the distribution used for it. This decides only what is
	 * wanted; whether the result stays inside the install folder is checked by the
	 * caller, which is the single place that answers that question.
	 */
	private void copyWhenWanted(ZipFile zip, ZipEntry entry, Path target) throws IOException {
		Path relative = wantedPath(entry);

		if (relative == null) {
			return;
		}

		Path destination = target.resolve(relative).normalize();

		// The only place that decides whether an entry may be written. Selecting by
		// folder name happens to reject every escaping name already, which is why no
		// test reaches this branch - but that is a property of the current filter, and
		// the guard is what keeps a change to it from turning into a written file.
		if (!destination.startsWith(target)) {
			log.warn("Ignoring archive entry written outside {}: {}", target, entry.getName());

			return;
		}

		copy(zip, entry, destination);
	}

	private Path wantedPath(ZipEntry entry) {
		Path full = Path.of(entry.getName()).normalize();

		if (entry.isDirectory() || full.getNameCount() < 3) {
			return null;
		}

		Path relative = full.subpath(1, full.getNameCount());

		return WANTED.contains(relative.getName(0).toString()) ? relative : null;
	}

	private void copy(ZipFile zip, ZipEntry entry, Path target) throws IOException {
		Path parent = target.getParent();

		if (parent != null) {
			Files.createDirectories(parent);
		}

		try (InputStream input = zip.getInputStream(entry)) {
			Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private void deleteQuietly(Path file) {
		if (file == null) {
			return;
		}

		try {
			Files.deleteIfExists(file);
		} catch (IOException exception) {
			log.debug("Could not delete the downloaded archive {}", file, exception);
		}
	}
}