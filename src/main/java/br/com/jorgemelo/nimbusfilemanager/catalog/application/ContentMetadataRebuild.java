package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import java.nio.file.Path;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationPathKey;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Asks, durably, for one file's metadata to be extracted again after its
 * content changed.
 *
 * <p>
 * Named for that rather than for the command it enqueues: the metadata domain
 * has its own launcher, which is the folder-wide rebuild a person asks for from
 * a screen. This is the catalog noticing that one file's bytes are not what it
 * recorded, and asking for that file alone. Two classes with one name were two
 * beans with one name, and the application could not start at all.
 *
 * <p>
 * Needed because metadata is the one kind of derived state nothing goes looking
 * for. A missing fingerprint is found by the backlog that selects files without
 * one; a missing set of dimensions is found by nobody, because the rebuild is a
 * command over a folder rather than a search for gaps. So when a change of
 * content clears it, the request to put it back has to be recorded in the same
 * transaction - a row that survives restart, not an intention held in memory by
 * whichever thread noticed.
 *
 * <p>
 * Scoped to the file's own path. The rebuild selects the path it is given and
 * everything under it, so a file path selects exactly that file.
 */
@Service
public class ContentMetadataRebuild {

	private final ExecutionEnqueueService executionEnqueueService;

	public ContentMetadataRebuild(ExecutionEnqueueService executionEnqueueService) {
		this.executionEnqueueService = executionEnqueueService;
	}

	public void rebuild(CatalogFile file, ExecutionTrigger trigger) {
		if (file.getLocation() == null) {
			return;
		}

		String path = PathUtils.normalize(file.getLocation().getCurrentPath());

		executionEnqueueService.enqueue(Execution.builder().executionType(ExecutionType.METADATA_REBUILD)
				.triggerEvent(trigger).sourcePath(path).executeFlag(true)
				.dedupKey(OperationPathKey.canonical(Path.of(path))).build());
	}
}