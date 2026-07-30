package br.com.jorgemelo.nimbusfilemanager.metadata.application.family;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.FileNameDatePatterns;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.filename.rule.AbstractFileNameDateRule;

/**
 * Date-only family: last-resort capture date for any name carrying an
 * {@code yyyyMMdd} (optionally {@code _HHmmss}) block, regardless of source.
 * Has no subcategory - a bare date doesn't identify the origin - so it
 * participates only in the filename date engine, ordered last.
 */
@Component
public class GenericMediaFamily extends AbstractFileNameDateRule {

	public GenericMediaFamily(Clock clock) {
		super(clock);
	}

	private static final String ORDER = "100_GENERIC";

	private static final Pattern SUPPORTS = Pattern.compile(".*\\d{8}.*");

	@Override
	public boolean supports(String fileName) {
		return fileName != null && SUPPORTS.matcher(fileName).matches();
	}

	@Override
	public LocalDateTime resolve(String fileName) {
		LocalDateTime date = parse(fileName, FileNameDatePatterns.DATE8_UNDERSCORE_TIME6, "yyyyMMddHHmmss");

		return date != null ? date : parse(fileName, FileNameDatePatterns.DATE8, "yyyyMMdd");
	}

	@Override
	public String name() {
		return ORDER;
	}
}