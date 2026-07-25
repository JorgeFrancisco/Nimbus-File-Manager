package br.com.jorgemelo.nimbusfilemanager.notification.application;

import java.util.List;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import lombok.extern.slf4j.Slf4j;

/**
 * Entry point every other class calls to send an email - callers never talk to
 * a specific {@link EmailProvider} directly. Providers are injected as a list
 * (Spring collects every {@code @Component} implementing {@link EmailProvider},
 * ordered by their {@code @Order} annotation) and the first one that reports
 * {@link EmailProvider#isConfigured()} wins; adding SendGrid/Amazon
 * SES/Mailgun/Postmark later is just a new {@link EmailProvider} bean, no
 * change needed here.
 * <p>
 * With no provider configured (the default out of the box, since email sending
 * is opt-in - see application.properties), this falls back to logging the
 * content instead, so local/dev use keeps working without any credentials, same
 * stand-in behaviour as before this feature existed.
 */
@Slf4j
@Service
public class EmailService extends LocalizedComponent {

	private final List<EmailProvider> providers;

	public EmailService(List<EmailProvider> providers) {
		this.providers = providers;
	}

	public void sendConfirmationEmail(String to, String confirmationLink) {
		String subject = message("backend.email.confirm.subject");
		String body = message("backend.email.confirm.body", confirmationLink);

		EmailProvider provider = providers.stream().filter(EmailProvider::isConfigured).findFirst().orElse(null);

		if (provider == null) {
			log.warn("Confirmation link for {}: {} (email sending is not configured yet)", to, confirmationLink);

			return;
		}

		try {
			provider.send(to, subject, body);

			log.info("Confirmation email sent to {} via {}", to, provider.name());
		} catch (RuntimeException exception) {
			log.warn("Failed to send confirmation email to {} via {} - logging link instead", to, provider.name(),
					exception);
			log.warn("Confirmation link for {}: {}", to, confirmationLink);
		}
	}
}