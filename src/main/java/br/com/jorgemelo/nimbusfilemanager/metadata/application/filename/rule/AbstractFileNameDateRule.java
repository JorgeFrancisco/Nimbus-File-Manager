package br.com.jorgemelo.nimbusfilemanager.metadata.application.filename.rule;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.date.CaptureYearRange;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.filename.FileNameDateRule;

public abstract class AbstractFileNameDateRule implements FileNameDateRule {

	protected final Clock clock;

	protected AbstractFileNameDateRule(Clock clock) {
		this.clock = clock;
	}

	protected LocalDateTime parse(String fileName, Pattern pattern, String datePattern) {
		Matcher matcher = pattern.matcher(fileName);

		if (!matcher.find()) {
			return null;
		}

		StringBuilder value = new StringBuilder();

		for (int i = 1; i <= matcher.groupCount(); i++) {
			value.append(matcher.group(i));
		}

		try {
			LocalDateTime date;

			if ("yyyyMMdd".equals(datePattern)) {
				date = LocalDate.parse(value.toString(), DateTimeFormatter.ofPattern(datePattern)).atStartOfDay();
			} else {
				date = LocalDateTime.parse(value.toString(), DateTimeFormatter.ofPattern(datePattern));
			}

			return isReasonable(date) ? date : null;
		} catch (Exception _) {
			return null;
		}
	}

	/**
	 * Date carried as epoch milliseconds in the file name, the convention of the
	 * apps that save with their own timestamp instead of the camera one. The
	 * pattern must expose the numeric token as its first group.
	 */
	protected LocalDateTime fromEpochMillis(String fileName, Pattern pattern) {
		if (fileName == null) {
			return null;
		}

		Matcher matcher = pattern.matcher(fileName);

		if (!matcher.find()) {
			return null;
		}

		try {
			LocalDateTime date = LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(matcher.group(1))),
					clock.getZone());

			return isReasonable(date) ? date : null;
		} catch (RuntimeException _) {
			return null;
		}
	}

	protected boolean isReasonable(LocalDateTime date) {
		return date != null && CaptureYearRange.isPlausible(date.getYear(), clock);
	}
}