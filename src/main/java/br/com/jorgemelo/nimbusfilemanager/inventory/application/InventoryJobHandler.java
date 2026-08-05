package br.com.jorgemelo.nimbusfilemanager.inventory.application;

import java.util.List;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryScanRequest;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.ScanOptions;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataOptions;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Runs an inventory that came off the queue.
 *
 * <p>
 * Everything it needs is already on the row - the folder is {@code sourcePath}
 * and whether to recurse is {@code recursive} - which is why INVENTORY is one
 * of the types that needs no request payload at all. The exclusions are read
 * here, when the pass starts, for the same reason the launcher reads them: they
 * are a setting, and a scan that picked up a change halfway through would be
 * scanning two different libraries.
 */
@Component
public class InventoryJobHandler implements ExecutionJobHandler {

	private final InventoryScanRunner inventoryScanRunner;
	private final ScanExclusionService scanExclusionService;

	public InventoryJobHandler(InventoryScanRunner inventoryScanRunner, ScanExclusionService scanExclusionService) {
		this.inventoryScanRunner = inventoryScanRunner;
		this.scanExclusionService = scanExclusionService;
	}

	/**
	 * Reading the tree and reconciling the catalog leaves nothing behind that a
	 * second pass would double, so an abandoned one is simply run again.
	 */
	@Override
	public boolean resumable() {
		return true;
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.INVENTORY;
	}

	/**
	 * No checkpoint between batches: this handler reads the tree and writes the
	 * catalog, and losing the locks halfway through leaves nothing on disk to be
	 * wrong - a checkpoint belongs where a mutation would be committed. The
	 * ownership still travels with it, because every report about the row is a
	 * write about a taking.
	 */
	@Override
	public void handle(Execution execution, ClaimedExecution claimed, ExecutionOwnership ownership) {
		ScanOptions scanOptions = new ScanOptions(Boolean.TRUE.equals(execution.getRecursive()), false, List.of(),
				scanExclusionService.excludedExtensions(), scanExclusionService.excludedFolders());

		InventoryScanRequest request = new InventoryScanRequest(PathUtils.normalizePath(claimed.sourcePath()),
				scanOptions, new MetadataOptions(true, false));

		inventoryScanRunner.run(execution, request, ownership);
	}
}