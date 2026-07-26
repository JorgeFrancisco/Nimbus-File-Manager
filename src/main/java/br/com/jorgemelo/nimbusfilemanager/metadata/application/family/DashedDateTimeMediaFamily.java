package br.com.jorgemelo.nimbusfilemanager.metadata.application.family;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.filename.rule.AbstractFileNameDateRule;

/**
 * Date-only family for the {@code yyyy-MM-dd HH.mm.ss} (and {@code _}/{@code -}
 * separated) layout. No subcategory; participates only in the filename date
 * engine, just before the generic fallback.
 */
@Component
public class DashedDateTimeMediaFamily extends AbstractFileNameDateRule {

	public DashedDateTimeMediaFamily(Clock clock) {
		super(clock);
	}

	private static final String ORDER = "090_DASHED_DATE_TIME";

	private static final Pattern SUPPORTS = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}.*");

	private static final Pattern EXTRACT = Pattern
			.compile("^(\\d{4})-(\\d{2})-(\\d{2})[ _-](\\d{2})[.-](\\d{2})[.-](\\d{2}).*", Pattern.CASE_INSENSITIVE);

	@Override
	public boolean supports(String fileName) {
		return fileName != null && SUPPORTS.matcher(fileName).matches();
	}

	@Override
	public LocalDateTime resolve(String fileName) {
		return parse(fileName, EXTRACT, "yyyyMMddHHmmss");
	}

	@Override
	public String name() {
		return ORDER;
	}
}