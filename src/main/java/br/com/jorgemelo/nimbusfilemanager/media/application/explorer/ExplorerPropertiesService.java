package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.media.application.dto.ExplorerItemProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.FolderInventorySummary;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.DateTimeFormatUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.ExtensionUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.SizeFormatter;

/**
 * Fills the properties dialog of the file explorer.
 *
 * <p>
 * A folder is described from the catalog, not by walking the disk: the catalog
 * answers in one query no matter how large the folder is, while a walk over a
 * drive root would keep the dialog waiting and still return only a snapshot.
 * The trade is that the counters cover what has been inventoried, so the dialog
 * says exactly that instead of presenting them as a disk total.
 */
@Service
@Transactional(readOnly = true)
public class ExplorerPropertiesService extends LocalizedComponent {

	private final CatalogFileRepository catalogFileRepository;

	public ExplorerPropertiesService(CatalogFileRepository catalogFileRepository) {
		this.catalogFileRepository = catalogFileRepository;
	}

	public ExplorerItemProperties of(Path path) throws IOException {
		Path target = PathUtils.normalizePath(path.toString());

		BasicFileAttributes attributes = Files.readAttributes(target, BasicFileAttributes.class);

		return attributes.isDirectory() ? folder(target, attributes) : file(target, attributes);
	}

	private ExplorerItemProperties file(Path target, BasicFileAttributes attributes) {
		boolean cataloged = catalogFileRepository.findByFileKey(PathUtils.normalize(target)).isPresent();

		String extension = ExtensionUtils.fromPath(target);

		return new ExplorerItemProperties(fileName(target), PathUtils.normalize(target), parent(target), false,
				attributes.size(), SizeFormatter.format(attributes.size()), null, null,
				extension.isBlank() ? message("files.properties.typeUnknown") : extension.toUpperCase(Locale.ROOT),
				label(attributes.creationTime().toMillis()), label(attributes.lastModifiedTime().toMillis()), cataloged,
				message(cataloged ? "files.status.cataloged" : "files.status.notCataloged"));
	}

	private ExplorerItemProperties folder(Path target, BasicFileAttributes attributes) {
		String folder = PathUtils.normalize(target);

		FolderInventorySummary summary = catalogFileRepository.summarizeFolder(folder,
				PathUtils.descendantLikePattern(folder, target.getFileSystem().getSeparator()));

		long sizeBytes = summary.getSizeBytes() == null ? 0 : summary.getSizeBytes();

		// The folder itself counts as one of the distinct folders the query grouped by,
		// so only what is nested below it is reported as a subfolder.
		long subfolders = Math.max(0, summary.getFolderCount() - 1);

		return new ExplorerItemProperties(fileName(target), folder, parent(target), true, sizeBytes,
				SizeFormatter.format(sizeBytes), summary.getFileCount(), subfolders,
				message("files.properties.typeFolder"), label(attributes.creationTime().toMillis()),
				label(attributes.lastModifiedTime().toMillis()), false, null);
	}

	private String fileName(Path path) {
		Path name = path.getFileName();

		return name == null ? PathUtils.normalize(path) : name.toString();
	}

	private String parent(Path path) {
		Path parent = path.getParent();

		return parent == null ? null : PathUtils.normalize(parent);
	}

	private String label(long epochMilli) {
		return DateTimeFormatUtils
				.human(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneId.systemDefault()));
	}
}