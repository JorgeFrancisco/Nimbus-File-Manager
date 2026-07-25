package br.com.jorgemelo.nimbusfilemanager.security.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class TwoFactorServiceTest {

	private final Clock clock = Clock.fixed(Instant.parse("2026-07-08T12:00:00Z"), ZoneOffset.UTC);
	private final TwoFactorService service = new TwoFactorService(clock);

	@Test
	void verifyShouldAcceptCodeGeneratedForCurrentWindow() {
		String secret = "JBSWY3DPEHPK3PXP";

		long counter = clock.instant().getEpochSecond() / 30;

		String code = service.generate(secret, counter);

		Assertions.assertThat(service.verify(secret, code)).isTrue();
	}

	@Test
	void verifyShouldAcceptAdjacentTimeWindow() {
		String secret = "JBSWY3DPEHPK3PXP";

		long counter = clock.instant().getEpochSecond() / 30;

		String previousCode = service.generate(secret, counter - 1);

		Assertions.assertThat(service.verify(secret, previousCode)).isTrue();
	}

	@Test
	void verifyShouldRejectInvalidInput() {
		Assertions.assertThat(service.verify(null, "123456")).isFalse();
		Assertions.assertThat(service.verify("JBSWY3DPEHPK3PXP", null)).isFalse();
		Assertions.assertThat(service.verify("JBSWY3DPEHPK3PXP", "abc")).isFalse();
		Assertions.assertThat(service.verify("invalid", "123456")).isFalse();
		Assertions.assertThat(service.verify("JBSWY3DPEHPK3PXP", "000000")).isFalse();
	}

	@Test
	void verifyShouldAcceptNextTimeWindow() {
		// Clock-skew tolerance is symmetric: besides the current and previous 30s
		// steps, a code from the NEXT step (offset +1) must also verify. This pins the
		// upper window boundary, which the current/previous-only tests leave unguarded.
		String secret = "JBSWY3DPEHPK3PXP";

		long counter = clock.instant().getEpochSecond() / 30;

		String nextCode = service.generate(secret, counter + 1);

		Assertions.assertThat(service.verify(secret, nextCode)).isTrue();
	}

	@Test
	void verifyShouldRejectCodeOutsideTheWindow() {
		// A code two steps away is outside the ±1 window and must be rejected,
		// otherwise the tolerance would be unbounded.
		String secret = "JBSWY3DPEHPK3PXP";

		long counter = clock.instant().getEpochSecond() / 30;

		Assertions.assertThat(service.verify(secret, service.generate(secret, counter + 2))).isFalse();
		Assertions.assertThat(service.verify(secret, service.generate(secret, counter - 2))).isFalse();
	}

	@Test
	void generateMatchesRfc4226ReferenceVectors() {
		// RFC 4226 Appendix D reference values for the ASCII secret
		// "12345678901234567890" (Base32 "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"). Asserting
		// the produced codes against the published vectors pins the HOTP/TOTP
		// truncation math to the spec - a non-self-referential check that breaks if any
		// bit operation drifts, which would silently break interop with authenticator
		// apps.
		String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

		String[] expected = { "755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583", "399871",
				"520489" };

		for (int counter = 0; counter < expected.length; counter++) {
			Assertions.assertThat(service.generate(secret, counter)).isEqualTo(expected[counter]);
		}
	}

	@Test
	void newSecretProducesDistinctNonEmptyBase32Secrets() {
		String first = service.newSecret();
		String second = service.newSecret();

		Assertions.assertThat(first).isNotBlank().matches("[A-Z2-7]+");
		// Two freshly generated secrets must differ - a fixed or empty secret would
		// defeat 2FA.
		Assertions.assertThat(second).isNotBlank().isNotEqualTo(first);
	}
}