package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;

/**
 * Serializes the typed arguments of an {@link ExecutionMessage} to the JSON
 * array persisted in the {@code message_args} column, and reads them back for
 * localization on the response side. Uses the project's configured Jackson
 * {@link ObjectMapper} so the round-trip matches the rest of the app. An empty
 * argument list is stored as {@code null} (no {@code message_args}), and a
 * {@code null}/blank column decodes back to an empty argument array.
 */
@Component
public class ExecutionMessageCodec {

	private static final Object[] NO_ARGS = new Object[0];

	private final ObjectMapper objectMapper;

	public ExecutionMessageCodec(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public String encode(List<Object> args) {
		if (args == null || args.isEmpty()) {
			return null;
		}

		try {
			return objectMapper.writeValueAsString(args);
		} catch (Exception e) {
			throw new IllegalStateException("Could not encode execution message arguments", e);
		}
	}

	public Object[] decode(String json) {
		if (json == null || json.isBlank()) {
			return NO_ARGS;
		}

		try {
			return objectMapper.readValue(json, Object[].class);
		} catch (Exception e) {
			throw new IllegalStateException("Could not decode execution message arguments", e);
		}
	}
}