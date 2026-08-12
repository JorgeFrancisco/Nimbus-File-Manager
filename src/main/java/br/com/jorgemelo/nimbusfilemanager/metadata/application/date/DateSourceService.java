package br.com.jorgemelo.nimbusfilemanager.metadata.application.date;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.CatalogTimestamp;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileValidationUtils;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.FileSystemDates;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.filename.FileNameDateRuleEngine;

@Service
public class DateSourceService {

	private final FolderLayoutDateResolver folderLayoutDateResolver;
	private final FileNameDateRuleEngine fileNameDateRuleEngine;
	private final CaptureDateValidator captureDateValidator;
	private final FileDateReader fileDateReader;
	private final Clock clock;

	@Autowired
	public DateSourceService(FolderLayoutDateResolver folderLayoutDateResolver,
			FileNameDateRuleEngine fileNameDateRuleEngine, CaptureDateValidator captureDateValidator, Clock clock) {
		this(folderLayoutDateResolver, fileNameDateRuleEngine, captureDateValidator, new DefaultFileDateReader(),
				clock);
	}

	DateSourceService(FolderLayoutDateResolver folderLayoutDateResolver, FileNameDateRuleEngine fileNameDateRuleEngine,
			CaptureDateValidator captureDateValidator, FileDateReader fileDateReader, Clock clock) {
		this.folderLayoutDateResolver = folderLayoutDateResolver;
		this.fileNameDateRuleEngine = fileNameDateRuleEngine;
		this.captureDateValidator = captureDateValidator;
		this.clock = clock;
		this.fileDateReader = fileDateReader;
	}

	/**
	 * Both dates from one {@link Files#readAttributes} call rather than two stat
	 * calls per file.
	 *
	 * <p>
	 * This replaced a pair of methods that read one date each, and they were
	 * removed rather than kept beside it: they held a second copy of the same
	 * fallback rule, and - the reason it matters here - they were the only place
	 * left that turned a filesystem instant into a catalog date without passing it
	 * through {@link CatalogTimestamp}. Nothing called them, so nothing was wrong
	 * yet; the next caller would have been.
	 */
	public FileSystemDates resolveFileSystemDates(Path file) {
		FileValidationUtils.validateFile(file);

		try {
			BasicFileAttributes attrs = fileDateReader.readAttributes(file);

			FileTime createdTime = attrs.creationTime() != null ? attrs.creationTime() : attrs.lastModifiedTime();

			// At the precision the catalog keeps, and here rather than at the writer:
			// these two become CatalogFile.createdAt and modifiedAt, and a value finer
			// than the column comes back different from what was written - which every
			// later comparison then reads as a file that changed.
			Instant createdAt = captureDateValidator.validate(CatalogTimestamp.observed(createdTime));
			Instant modifiedAt = captureDateValidator.validate(CatalogTimestamp.observed(attrs.lastModifiedTime()));

			return new FileSystemDates(createdAt, modifiedAt);
		} catch (IOException e) {
			throw new IllegalStateException("Could not read file dates: " + file, e);
		}
	}

	/**
	 * Reads a filesystem timestamp as a capture date.
	 *
	 * <p>
	 * The two are different kinds of value and this is the only place allowed to
	 * cross between them. A {@code FileTime} is a position on the timeline; a
	 * capture date is what a clock on the wall said when the picture was taken,
	 * with no offset attached. Turning the first into the second requires choosing
	 * a zone, and the choice is the application's configured one - the same one the
	 * date is later shown in - rather than whatever zone the machine happens to be
	 * set to.
	 *
	 * <p>
	 * It is a fallback: a photo that carries its own capture date never comes
	 * through here.
	 */
	public LocalDateTime asCaptureDate(Instant timestamp) {
		return timestamp == null ? null : LocalDateTime.ofInstant(timestamp, clock.getZone());
	}

	public LocalDateTime resolveFromFileName(Path file) {
		FileValidationUtils.validateFile(file);

		var resolvedDate = fileNameDateRuleEngine.resolve(file.getFileName().toString());

		return captureDateValidator.validate(resolvedDate);
	}

	public LocalDateTime resolveFromFolderLayout(Path file) {
		FileValidationUtils.validateFile(file);

		var resolvedDate = folderLayoutDateResolver.resolve(file);

		return captureDateValidator.validate(resolvedDate);
	}
}