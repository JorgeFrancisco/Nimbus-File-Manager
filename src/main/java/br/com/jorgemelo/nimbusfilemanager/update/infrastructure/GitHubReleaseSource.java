package br.com.jorgemelo.nimbusfilemanager.update.infrastructure;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.UpdateProperties;
import br.com.jorgemelo.nimbusfilemanager.update.application.GitHubReleases;
import br.com.jorgemelo.nimbusfilemanager.update.application.ReleaseSource;
import br.com.jorgemelo.nimbusfilemanager.update.application.dto.PublishedRelease;
import lombok.extern.slf4j.Slf4j;

/**
 * Asks the releases endpoint what the most recent release is.
 *
 * <p>
 * Everything that can go wrong here ends the same way: empty. Being offline,
 * a name that does not resolve, a rate limit, a 404 from a repository that has
 * never published, a body that is not the document expected - none of them is a
 * fault of this installation, and none of them should be visible to the person
 * using it. A local-first application is expected to run for weeks without a
 * network, so this failing is the normal case rather than the exceptional one,
 * which is why it is logged at debug and never with a stack trace.
 *
 * <p>
 * The timeouts are short on purpose. Nothing waits on this answer - no screen
 * blocks, no start is held up - so a check that cannot complete in seconds has
 * already failed at being unobtrusive, and there will be another one.
 */
@Slf4j
@Component
public class GitHubReleaseSource implements ReleaseSource {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

	private final UpdateProperties properties;
	private final ObjectMapper objectMapper;
	private final HttpClient httpClient;

	public GitHubReleaseSource(UpdateProperties properties, ObjectMapper objectMapper) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL).build();
	}

	@Override
	public Optional<PublishedRelease> latest() {
		String url = properties.releaseUrl();

		if (!properties.enabled() || url == null || url.isBlank()) {
			return Optional.empty();
		}

		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT)
					.header("Accept", "application/vnd.github+json").GET().build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() != 200) {
				log.debug("The release endpoint answered HTTP {}", response.statusCode());

				return Optional.empty();
			}

			return GitHubReleases.parse(response.body(), objectMapper);
		} catch (InterruptedException _) {
			Thread.currentThread().interrupt();

			return Optional.empty();
		} catch (Exception exception) {
			log.debug("Could not ask {} about releases: {}", url, exception.getMessage());

			return Optional.empty();
		}
	}
}