package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.constants.CatalogConstants;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.constants.CatalogMessages;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.CatalogPurgePayload;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

/**
 * Asking for the catalog retention purge.
 *
 * <p>
 * It asks a cheap question first - is anything at all past the window? - and
 * queues nothing when the answer is no. The check is an optimisation and
 * nothing else: it exists so a quiet day leaves no row on the executions
 * screen, which is what the daily quarantine purge already does. What is
 * actually purged is decided by the worker, against the clock of the moment it
 * runs.
 */
@Service
public class CatalogPurgeLauncherService {

	private final CatalogFileRepository catalogFileRepository;
	private final ExecutionEnqueueService executionEnqueueService;
	private final ExecutionPayloadCodec executionPayloadCodec;
	private final ExecutionMessageCodec executionMessageCodec;
	private final Clock clock;

	public CatalogPurgeLauncherService(CatalogFileRepository catalogFileRepository,
			ExecutionEnqueueService executionEnqueueService, ExecutionPayloadCodec executionPayloadCodec,
			ExecutionMessageCodec executionMessageCodec, Clock clock) {
		this.catalogFileRepository = catalogFileRepository;
		this.executionEnqueueService = executionEnqueueService;
		this.executionPayloadCodec = executionPayloadCodec;
		this.executionMessageCodec = executionMessageCodec;
		this.clock = clock;
	}

	/**
	 * @return the queued purge, or empty when nothing is overdue and nothing was
	 * asked for
	 */
	public Optional<Execution> launch(int retentionDays) {
		if (retentionDays <= 0) {
			return Optional.empty();
		}

		// The same instant the pass itself computes, and the same type the column is
		// mapped to. It was a LocalDateTime here while catalog_file.lifecycleChangedAt
		// had already become an Instant: the derived query still compiled, and every
		// scheduled purge died in Hibernate on the argument type - so nothing missing
		// was ever aged out, and the only trace was one line in the log.
		Instant cutoff = Instant.now(clock).minus(Duration.ofDays(retentionDays));

		if (!catalogFileRepository.existsByLifecycleStatusAndLifecycleChangedAtBefore(LifecycleStatus.MISSING,
				cutoff)) {
			return Optional.empty();
		}

		var started = CatalogMessages.purgeStarted();

		return Optional.of(executionEnqueueService.enqueueOrExisting(Execution.builder()
				.executionType(ExecutionType.CATALOG_PURGE).triggerEvent(ExecutionTrigger.TIMER).recursive(false)
				.executeFlag(true).dedupKey(String.valueOf(retentionDays))
				.requestPayload(executionPayloadCodec
						.encode(new CatalogPurgePayload(CatalogConstants.PAYLOAD_SCHEMA_VERSION, retentionDays)))
				.statusMessage(StatusMessage.coded(started.code(), executionMessageCodec.encode(started.args())))
				.build()));
	}
}