package br.com.jorgemelo.nimbusfilemanager.settings.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.ExternalToolStatus;
import lombok.extern.slf4j.Slf4j;

/**
 * Installs ffmpeg and ffprobe into the folder {@link ExternalToolPaths}
 * discovers, so the operator never has to find a download, pick the right build
 * flavour and copy DLLs by hand - the step that made the manual setup the worst
 * part of a first run.
 *
 * <p>
 * Only the executables and their DLLs are kept: the published archive also
 * carries headers, docs and ffplay, none of which this application calls. The
 * license file travels with the binaries because the build is GPL and the
 * license has to stay next to what it covers.
 *
 * <p>
 * The download is done by the machine that runs the application, not shipped
 * inside it. That is deliberate: distributing the GPL binary would put the
 * corresponding obligations on this project's releases, while fetching it from
 * the official build keeps FFmpeg as the distributor.
 */
@Slf4j
@Service
public class ExternalToolInstaller {

	private static final String LICENSE_ENTRY = "LICENSE.txt";
	private static final String LICENSE_FILE = "FFMPEG-LICENSE.txt";
	private static final List<String> WANTED = List.of("ffmpeg.exe", "ffprobe.exe", "ffmpeg", "ffprobe");

	private final ExternalToolPaths externalToolPaths;
	private final ExternalToolArchiveSource archiveSource;
	private final ExternalToolProbe probe;
	private final ExternalToolInstallProgress progress;

	public ExternalToolInstaller(ExternalToolPaths externalToolPaths, ExternalToolArchiveSource archiveSource,
			ExternalToolProbe probe, ExternalToolInstallProgress progress) {
		this.externalToolPaths = externalToolPaths;
		this.archiveSource = archiveSource;
		this.probe = probe;
		this.progress = progress;
	}

	/** Downloads and unpacks the tools. Blocking; run it off the request thread. */
	public ExternalToolStatus install() {
		Path directory = externalToolPaths.toolsDirectory();

		Path archive = null;

		try {
			Files.createDirectories(directory);

			archive = archiveSource.download(directory);

			extract(archive, directory);
		} catch (IOException e) {
			throw new IllegalStateException("Could not install the external tools into " + directory, e);
		} finally {
			deleteQuietly(archive);
		}

		log.info("External tools installed into {}", directory);

		return status();
	}

	public ExternalToolStatus status() {
		String ffmpeg = externalToolPaths.ffmpeg();
		String ffprobe = externalToolPaths.ffprobe();

		Optional<String> version = probe.version(ffmpeg);

		Path directory = externalToolPaths.toolsDirectory();

		return new ExternalToolStatus(version.isPresent(), ffmpeg, probe.version(ffprobe).isPresent(), ffprobe,
				version.orElse(null), isInside(ffmpeg, directory), isWindows(), directory.toAbsolutePath().toString());
	}

	/**
	 * Copies the executables and their DLLs out of the archive, flattening the
	 * {@code <build>/bin/} folder the publisher wraps them in. Entries are taken by
	 * file name only, which also makes a crafted path inside the archive unable to
	 * escape the target folder.
	 */
	private void extract(Path archive, Path directory) throws IOException {
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			progress.startExtraction(wantedBytes(zip));

			Enumeration<? extends ZipEntry> entries = zip.entries();

			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();

				String name = fileNameOf(entry);

				if (!isWanted(entry)) {
					continue;
				}

				copy(zip, entry, directory.resolve(LICENSE_ENTRY.equals(name) ? LICENSE_FILE : name));
			}
		}
	}

	private void copy(ZipFile zip, ZipEntry entry, Path target) throws IOException {
		Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".part");

		try (InputStream source = zip.getInputStream(entry)) {
			Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);

			// Replacing a running executable fails on Windows; the caller turns that
			// into a message telling the operator to stop what is using ffmpeg.
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);

			progress.addExtractedBytes(entry.getSize() > 0 ? entry.getSize() : 0);
		} finally {
			deleteQuietly(temp);
		}
	}

	private long wantedBytes(ZipFile zip) {
		return zip.stream().filter(this::isWanted).mapToLong(ZipEntry::getSize).filter(size -> size > 0).sum();
	}

	private boolean isWanted(ZipEntry entry) {
		if (entry.isDirectory()) {
			return false;
		}

		String name = fileNameOf(entry);

		return WANTED.contains(name) || name.endsWith(".dll") || LICENSE_ENTRY.equals(name);
	}

	private String fileNameOf(ZipEntry entry) {
		String name = entry.getName().replace('\\', '/');

		return name.substring(name.lastIndexOf('/') + 1);
	}

	/** True when the resolved command is the copy this installer manages. */
	private boolean isInside(String executable, Path directory) {
		return Path.of(executable).toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize());
	}

	/**
	 * The published archive this installer unpacks is a Windows build. Elsewhere
	 * the package manager is the right answer ({@code apt install ffmpeg}), so the
	 * screen offers instructions instead of a button.
	 */
	private boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	private void deleteQuietly(Path file) {
		if (file == null) {
			return;
		}

		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			log.debug("Could not delete {}", file, e);
		}
	}
}