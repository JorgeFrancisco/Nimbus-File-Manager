package br.com.jorgemelo.nimbusfilemanager.security.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates and publishes the one-time password of a brand-new installation.
 *
 * <p>
 * A password shipped in the repository is the same on every installation that
 * ever runs the application, and it is published wherever the source is. The
 * mandatory change on first login does not close that: between the first start
 * and the first login, anyone who reaches the port can sign in with the known
 * value - and, because the change is mandatory, the first one in is the one who
 * sets the new password and keeps the account. Generating the password per
 * installation removes the window instead of shortening it.
 *
 * <p>
 * The value is written both to the log and to a file in the workspace, because
 * neither channel alone reaches every operator: a container user reads the
 * console and never opens the workspace, while someone running it as a Windows
 * service sees no console at all. The file says to delete itself once the
 * password has been changed; no attempt is made to restrict its permissions,
 * which would behave differently on each platform and give false assurance -
 * the workspace is the user's own folder, and the credential is single-use.
 */
@Slf4j
@Component
public class FirstAccessCredential {

	/**
	 * Twelve random bytes, rendered as sixteen URL-safe characters. Well beyond
	 * what a password that lives for one login needs, and short enough to be typed
	 * from a console by hand.
	 */
	private static final int PASSWORD_BYTES = 12;

	private static final String FILE_NAME = "first-access.txt";

	private final WorkspaceManager workspaceManager;
	private final SecureRandom random = new SecureRandom();

	public FirstAccessCredential(WorkspaceManager workspaceManager) {
		this.workspaceManager = workspaceManager;
	}

	public String generate() {
		byte[] bytes = new byte[PASSWORD_BYTES];

		random.nextBytes(bytes);

		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/**
	 * Tells the operator the password, once. A workspace that cannot be written to
	 * is not a reason to fail the start: the log still carries the credential, and
	 * an installation with no way in would be worse than one with an inconvenient
	 * way in.
	 */
	public void publish(String username, String password) {
		log.warn("""

				=====================================================================
				 Nimbus File Manager - first access
				 user:     {}
				 password: {}
				 This password is shown once and must be changed at first login.
				=====================================================================
				""", username, password);

		try {
			Path file = workspaceManager.resolve(FILE_NAME);

			Files.createDirectories(file.getParent());
			Files.writeString(file, """
					Nimbus File Manager - first access

					user:     %s
					password: %s

					This password was generated for this installation and is shown only once.
					It has to be changed at first login. Delete this file afterwards.
					""".formatted(username, password));

			log.warn("First access credential also written to {}", file);
		} catch (IOException e) {
			log.warn("Could not write the first access credential to the workspace ({}); use the password above",
					e.getMessage());
		}
	}
}