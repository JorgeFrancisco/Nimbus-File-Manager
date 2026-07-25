package br.com.jorgemelo.nimbusfilemanager.notification.infrastructure;

import java.util.Properties;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.notification.application.EmailProvider;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Gmail;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;

/**
 * Sends email through a personal Gmail account's SMTP server - meant for
 * self-hosted/personal use (small volume, e.g. account confirmation links), not
 * high-throughput transactional email.
 * <p>
 * Needs a Google "App Password" (Google Account &gt; Security &gt; 2-Step
 * Verification &gt; App passwords - requires 2-Step Verification to be turned
 * on first), never the account's regular password. Credentials come from
 * {@code nimbus-file-manager.email.gmail.username}/{@code .password}, which in
 * turn read {@code NIMBUS_FILE_MANAGER_SMTP_USERNAME} /
 * {@code NIMBUS_FILE_MANAGER_SMTP_PASSWORD} environment variables (see
 * application.properties) - they are never committed to the repository or
 * stored in the database.
 * <p>
 * Deliberately does <b>not</b> use Spring Boot's auto-configured
 * {@code spring.mail.*} properties / shared {@link JavaMailSender} bean: that
 * mechanism only supports one host/port/credential set for the whole app, which
 * would collide the moment a second SMTP-based provider (Mailgun, Amazon SES,
 * ...) is added. Instead this class builds and owns its own
 * {@link JavaMailSenderImpl}, scoped to smtp.gmail.com only - the pattern every
 * other {@link EmailProvider} should follow, each with its own property
 * namespace (some, like SendGrid or Postmark, would use an HTTP API client here
 * instead of SMTP at all).
 */
@Component
@Order(1)
public class GmailEmailProvider implements EmailProvider {

	private static final String SMTP_HOST = "smtp.gmail.com";
	private static final int SMTP_PORT = 587;

	private final JavaMailSender mailSender;
	private final boolean enabled;
	private final String username;
	private final String password;

	@Autowired
	public GmailEmailProvider(NimbusFileManagerProperties properties) {
		this(properties.email().gmail());
	}

	private GmailEmailProvider(Gmail gmail) {
		this(buildMailSender(gmail.username(), gmail.password()), gmail.enabled(), gmail.username(), gmail.password());
	}

	/**
	 * Package-private constructor used by tests to inject a fake
	 * {@link JavaMailSender} instead of one that would try to reach smtp.gmail.com.
	 */
	GmailEmailProvider(JavaMailSender mailSender, boolean enabled, String username, String password) {
		this.mailSender = mailSender;
		this.enabled = enabled;
		this.username = username;
		this.password = password;
	}

	private static JavaMailSender buildMailSender(String username, String password) {
		JavaMailSenderImpl sender = new JavaMailSenderImpl();

		sender.setHost(SMTP_HOST);
		sender.setPort(SMTP_PORT);
		sender.setUsername(username);
		sender.setPassword(password);

		Properties properties = sender.getJavaMailProperties();

		properties.put("mail.smtp.auth", "true");
		properties.put("mail.smtp.starttls.enable", "true");

		return sender;
	}

	@Override
	public String name() {
		return "Gmail SMTP";
	}

	@Override
	public boolean isConfigured() {
		return enabled && username != null && !username.isBlank() && password != null && !password.isBlank();
	}

	@Override
	public void send(String to, String subject, String body) {
		SimpleMailMessage message = new SimpleMailMessage();

		message.setFrom(username);
		message.setTo(to);
		message.setSubject(subject);
		message.setText(body);

		mailSender.send(message);
	}
}