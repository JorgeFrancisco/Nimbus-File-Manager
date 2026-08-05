package br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/**
 * That the script reaches PowerShell as it was written.
 *
 * <p>
 * The two plainer ways of handing it over both lost characters: as an argument
 * of {@code -Command} the quotes were rewritten by the command line the JVM
 * builds, and through standard input the text was decoded with the console's OEM
 * code page, which turned UTF-8 into mojibake and had no room for a Japanese
 * folder name at all.
 */
class WindowsPowerShellTest {

	private static final String SCRIPT = "Start-Process -ArgumentList "
			+ "'\"--path=D:\\Vídeos & Ação (2026)\\Müller\\日本\\\"'";

	/**
	 * Decoded the way PowerShell decodes it - UTF-16LE, which is what PowerShell
	 * is - the script is the same string, character for character.
	 */
	@Test
	void survivesTheEncodingPowerShellReadsItWith() {
		String decoded = new String(Base64.getDecoder().decode(WindowsPowerShell.encodedCommand(SCRIPT)),
				StandardCharsets.UTF_16LE);

		assertThat(decoded).isEqualTo(SCRIPT);
		assertThat(decoded.codePoints().toArray()).containsExactly(SCRIPT.codePoints().toArray());
	}

	/**
	 * Nothing in the argument needs quoting, which is the other half of why this is
	 * used: whatever builds the command line for PowerShell has nothing to escape.
	 */
	@Test
	void producesAnArgumentWithNothingToInterpret() {
		assertThat(WindowsPowerShell.encodedCommand(SCRIPT)).matches("[A-Za-z0-9+/]+=*");
	}
}