package br.com.jorgemelo.nimbusfilemanager.metadata.application.date;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileValidationUtils;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.FileSystemDates;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.filename.FileNameDateRuleEngine;

@Service
public class DateSourceService {

	private final FolderLayoutDateResolver folderLayoutDateResolver;
	private final FileNameDateRuleEngine fileNameDateRuleEngine;
	private final CaptureDateValidator captureDateValidator;
	private final FileDateReader fileDateReader;

	@Autowired
	public DateSourceService(FolderLayoutDateResolver folderLayoutDateResolver,
			FileNameDateRuleEngine fileNameDateRuleEngine, CaptureDateValidator captureDateValidator) {
		this(folderLayoutDateResolver, fileNameDateRuleEngine, captureDateValidator, new DefaultFileDateReader());
	}

	DateSourceService(FolderLayoutDateResolver folderLayoutDateResolver, FileNameDateRuleEngine fileNameDateRuleEngine,
			CaptureDateValidator captureDateValidator, FileDateReader fileDateReader) {
		this.folderLayoutDateResolver = folderLayoutDateResolver;
		this.fileNameDateRuleEngine = fileNameDateRuleEngine;
		this.captureDateValidator = captureDateValidator;
		this.fileDateReader = fileDateReader;
	}

	LocalDateTime resolveFromFileSystem(Path file) {
		FileValidationUtils.validateFile(file);

		try {
			BasicFileAttributes attrs = fileDateReader.readAttributes(file);

			if (attrs.creationTime() != null) {
				var resolvedDate = LocalDateTime.ofInstant(attrs.creationTime().toInstant(), ZoneId.systemDefault());

				return captureDateValidator.validate(resolvedDate);
			}

			var resolvedDate = LocalDateTime.ofInstant(attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault());

			return captureDateValidator.validate(resolvedDate);
		} catch (IOException e) {
			throw new IllegalStateException("Could not read file dates: " + file, e);
		}
	}

	LocalDateTime resolveModifiedAt(Path file) {
		FileValidationUtils.validateFile(file);

		try {
			var resolvedDate = LocalDateTime.ofInstant(fileDateReader.getLastModifiedTime(file).toInstant(),
					ZoneId.systemDefault());

			return captureDateValidator.validate(resolvedDate);
		} catch (IOException e) {
			throw new IllegalStateException("Could not read modified date: " + file, e);
		}
	}

	/**
	 * Combines what {@link #resolveFromFileSystem(Path)} and
	 * {@link #resolveModifiedAt(Path)} do into a single
	 * {@link Files#readAttributes} call, instead of two separate filesystem stat
	 * calls per file. Semantics match those two methods exactly.
	 */
	public FileSystemDates resolveFileSystemDates(Path file) {
		FileValidationUtils.validateFile(file);

		try {
			BasicFileAttributes attrs = fileDateReader.readAttributes(file);

			FileTime createdTime = attrs.creationTime() != null ? attrs.creationTime() : attrs.lastModifiedTime();

			LocalDateTime createdAt = captureDateValidator
					.validate(LocalDateTime.ofInstant(createdTime.toInstant(), ZoneId.systemDefault()));
			LocalDateTime modifiedAt = captureDateValidator
					.validate(LocalDateTime.ofInstant(attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault()));

			return new FileSystemDates(createdAt, modifiedAt);
		} catch (IOException e) {
			throw new IllegalStateException("Could not read file dates: " + file, e);
		}
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