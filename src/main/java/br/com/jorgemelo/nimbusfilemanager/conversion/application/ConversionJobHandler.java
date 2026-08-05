package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.constants.ConversionConstants;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionExecutePayload;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionOptions;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Runs a conversion batch that came off the queue.
 *
 * <p>
 * One at a time, which is the default and the point of it: a second concurrent
 * H.265 encode makes both slower rather than either faster, and each holds its
 * own temporary file, its own memory and its own hours. That guarantee used to
 * be an {@code AtomicBoolean} in a runner that only answered for one process -
 * saying it here is what keeps it once the work can be claimed by another.
 *
 * <p>
 * Not resumable: the batch has already placed converted files in the library
 * and may have quarantined the originals they replaced. A second pass from the
 * start would begin from a library the first one changed. What it can do
 * instead is be asked for again, and skip what is already H.265 in MP4 - which
 * the eligibility check does on its own.
 */
@Component
public class ConversionJobHandler implements ExecutionJobHandler {

	private final VideoConversionService videoConversionService;
	private final ExecutionPayloadCodec executionPayloadCodec;

	public ConversionJobHandler(VideoConversionService videoConversionService,
			ExecutionPayloadCodec executionPayloadCodec) {
		this.videoConversionService = videoConversionService;
		this.executionPayloadCodec = executionPayloadCodec;
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.CONVERSION;
	}

	@Override
	public void handle(Execution execution, ClaimedExecution claimed, ExecutionOwnership ownership) {
		ConversionExecutePayload payload = payloadOf(claimed);

		videoConversionService.convert(payload.publicIds(), optionsOf(payload), execution, ownership);
	}

	private ConversionExecutePayload payloadOf(ClaimedExecution claimed) {
		ConversionExecutePayload payload = executionPayloadCodec.decode(claimed.requestPayload(),
				ConversionExecutePayload.class);

		if (payload.schemaVersion() == null
				|| payload.schemaVersion() != ConversionConstants.EXECUTE_PAYLOAD_SCHEMA_VERSION) {
			throw new IllegalArgumentException("Conversion payload schema " + payload.schemaVersion()
					+ " cannot be run by this version, which writes and reads schema "
					+ ConversionConstants.EXECUTE_PAYLOAD_SCHEMA_VERSION);
		}

		if (payload.publicIds() == null || payload.publicIds().isEmpty()) {
			throw new IllegalArgumentException("A conversion has to name the videos it converts");
		}

		return payload;
	}

	/**
	 * Rebuilt through the same factory the screen uses, so an option missing from
	 * an older payload falls back to what a request without it would have meant.
	 */
	private ConversionOptions optionsOf(ConversionExecutePayload payload) {
		return ConversionOptions.of(payload.quality(), payload.audio(), payload.disposition(), payload.nameAffix(),
				payload.affixPosition());
	}
}