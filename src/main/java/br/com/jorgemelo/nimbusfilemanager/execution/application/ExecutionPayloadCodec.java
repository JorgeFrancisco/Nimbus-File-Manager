package br.com.jorgemelo.nimbusfilemanager.execution.application;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The arguments a queued execution carries, to and from the
 * {@code request_payload} column.
 *
 * <p>
 * What goes in the column is only what the row cannot already say. The folder,
 * the destination and whether to recurse are columns of {@code execution} and
 * are read from there - repeating them here would create two answers to the
 * same question, and the locks are taken from the columns.
 *
 * <p>
 * A payload that will not read back is not guessed at. It comes back as an
 * {@link IllegalArgumentException}, which the retry policy treats as permanent:
 * running the same unreadable request again would fail the same way, and a job
 * that keeps being handed back is worse than one that ends visibly wrong.
 */
@Component
public class ExecutionPayloadCodec {

	private final ObjectMapper objectMapper;

	public ExecutionPayloadCodec(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * @return the JSON to store, or {@code null} for a type that needs no arguments
	 */
	public String encode(Object payload) {
		if (payload == null) {
			return null;
		}

		try {
			return objectMapper.writeValueAsString(payload);
		} catch (Exception exception) {
			// Whoever asked for the work built an object that cannot be written down.
			// That is a defect here, not a bad request, and it must not become a queued
			// row nobody can run.
			throw new IllegalStateException("Could not encode the request payload of " + payload.getClass(), exception);
		}
	}

	public <T> T decode(String json, Class<T> type) {
		if (json == null || json.isBlank()) {
			throw new IllegalArgumentException("A " + type.getSimpleName() + " was expected and the row carries none");
		}

		try {
			return objectMapper.readValue(json, type);
		} catch (Exception exception) {
			throw new IllegalArgumentException("Could not read the request payload as a " + type.getSimpleName(),
					exception);
		}
	}
}