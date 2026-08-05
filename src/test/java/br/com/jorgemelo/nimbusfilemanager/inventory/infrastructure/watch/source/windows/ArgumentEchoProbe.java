package br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.windows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes down the arguments it was actually given.
 *
 * <p>
 * The only way to know what survived the crossing. Everything before it - the
 * quoted string, the PowerShell literal - is what we believe we sent; this is
 * what a process on the other side received, split by Windows itself.
 *
 * <p>
 * A file rather than standard output, because the process is started detached by
 * {@code Start-Process} and there is no pipe back to read.
 */
public final class ArgumentEchoProbe {

	/** Where to write, named by the test. */
	public static final String OUTPUT_PROPERTY = "nimbus.argument.echo";

	private ArgumentEchoProbe() {
	}

	public static void main(String[] args) throws Exception {
		List<String> received = new ArrayList<>(List.of(args));

		Files.write(Path.of(System.getProperty(OUTPUT_PROPERTY)), received);
	}
}