package br.com.jorgemelo.nimbusfilemanager.metadata.application.date;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.ResolvedMediaDate;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.model.MetadataResult;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.MediaMetadata;

@Component
public class MediaDateResolver {

	private final CaptureDateValidator captureDateValidator;

	public MediaDateResolver(CaptureDateValidator captureDateValidator) {
		this.captureDateValidator = captureDateValidator;
	}

	public ResolvedMediaDate resolve(MetadataResult metadata) {
		LocalDateTime captureDate = captureDateValidator.validate(metadata.getCaptureDate());

		DateSource dateSource = captureDate == null ? null : metadata.getDateSource();

		return new ResolvedMediaDate(captureDate, dateSource);
	}

	/**
	 * Resolves the date and writes it onto the media row. Single owner of that
	 * assignment: the inventory mapper and the metadata rebuild both go through
	 * here, so a date field added later cannot be filled on one path and
	 * forgotten on the other.
	 */
	public void applyTo(MediaMetadata media, MetadataResult metadata) {
		ResolvedMediaDate resolvedDate = resolve(metadata);

		media.setYear(resolvedDate.year());
		media.setMonth(resolvedDate.month());
		media.setDay(resolvedDate.day());
		media.setYearMonth(resolvedDate.yearMonth());
		media.setCaptureDate(resolvedDate.captureDate());
		media.setDateSource(resolvedDate.dateSource());
	}
}