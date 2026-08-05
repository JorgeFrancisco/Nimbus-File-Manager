package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import static org.mockito.ArgumentMatchers.argThat;

import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;

/**
 * Matches the message a command records on its execution.
 *
 * <p>
 * By code and arguments rather than by text, which is the whole point of
 * recording it that way: the worker has no request behind it and no language,
 * so what a test can hold it to is the code it wrote and the values it filled
 * in. That the code exists in every bundle is somebody else's test.
 */
final class CarriedMessages {

	private CarriedMessages() {
	}

	static ExecutionMessage carrying(String code, Object... args) {
		return argThat(message -> message.code().equals(code) && message.args().equals(List.of(args)));
	}
}