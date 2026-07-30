package br.com.jorgemelo.nimbusfilemanager.metadata.application.family;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.date.CaptureYearRange;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.filename.rule.AbstractFileNameDateRule;

/**
 * Date-only family for the {@code MMddyyHHmmss} run that early camera phones
 * wrote as the whole file name ({@code 012708165237.jpg} is 2008-01-27
 * 16:52:37). No subcategory - a bare timestamp names no origin - so it feeds
 * only the filename date engine, ordered just before the generic digit scan.
 *
 * <p>
 * Twelve digits are also how the {@code ddMMyyyy} plus four-digit counter
 * layout names instrument and scanner output ({@code 031120081613 - 01.bmp}
 * is item 1613 of 2008-11-03). Both readings can be valid for the same run,
 * and the name carries nothing to break the tie, so a run whose fifth to
 * eighth digits already spell a plausible year is left to the remaining
 * resolvers instead of being dated by a coin flip.
 *
 * <p>
 * The two-digit year resolves against base 2000, which is what the format can
 * express: a phone that stamped it is later than 1999, and anything the base
 * pushes past next year is dropped by the plausibility guard.
 */
@Component
public class MonthFirstDateTimeMediaFamily extends AbstractFileNameDateRule {

	public MonthFirstDateTimeMediaFamily(Clock clock) {
		super(clock);
	}

	private static final String ORDER = "096_MONTH_FIRST_DATE_TIME";

	/** Twelve digits opening the name, not a slice of a longer numeric run. */
	private static final Pattern EXTRACT = Pattern.compile("^(\\d{12})(?!\\d)");

	/** {@code (01-31)(01-12)yyyy} then the counter: the competing layout. */
	private static final Pattern DAY_FIRST = Pattern
			.compile("^(?:0[1-9]|[12]\\d|3[01])(?:0[1-9]|1[0-2])(\\d{4})\\d{4}(?!\\d)");

	@Override
	public boolean supports(String fileName) {
		return fileName != null && EXTRACT.matcher(fileName).find();
	}

	@Override
	public LocalDateTime resolve(String fileName) {
		return isDayFirstWithCounter(fileName) ? null : parse(fileName, EXTRACT, "MMddyyHHmmss");
	}

	@Override
	public String name() {
		return ORDER;
	}

	private boolean isDayFirstWithCounter(String fileName) {
		Matcher matcher = DAY_FIRST.matcher(fileName);

		return matcher.find() && CaptureYearRange.isPlausible(Integer.parseInt(matcher.group(1)), clock);
	}
}