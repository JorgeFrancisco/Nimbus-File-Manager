package br.com.jorgemelo.nimbusfilemanager.notification.application;

/**
 * A backend capable of actually sending an email (Gmail SMTP, SendGrid, Amazon
 * SES, Mailgun, Postmark, ...). Implementations are picked up automatically by
 * {@link EmailService} - to add a new provider, implement this interface,
 * annotate the class with {@code @Component} and {@code @Order(n)} (controls
 * priority when more than one provider is configured at the same time), and
 * read whatever credentials/toggle it needs from typed configuration (e.g.
 * {@code @ConfigurationProperties}, as {@link GmailEmailProvider} does). No
 * other class needs to change.
 */
public interface EmailProvider {

	/**
	 * Short, human-readable name used in logs (e.g. "Gmail SMTP", "SendGrid").
	 */
	String name();

	/**
	 * Whether this provider has everything it needs to send right now (enabled flag
	 * plus non-blank credentials). {@link EmailService} only ever calls
	 * {@link #send} on a provider that answered true here.
	 */
	boolean isConfigured();

	/**
	 * Sends a plain-text email. Implementations may throw any
	 * {@link RuntimeException} (for example
	 * {@link org.springframework.mail.MailException}) on failure - the caller is
	 * responsible for deciding what to do next.
	 */
	void send(String to, String subject, String body);
}