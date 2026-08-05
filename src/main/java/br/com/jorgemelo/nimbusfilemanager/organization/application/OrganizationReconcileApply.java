package br.com.jorgemelo.nimbusfilemanager.organization.application;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.Scan;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * The reconcile that writes.
 *
 * <p>
 * Split from the scan because the two are different capabilities and only one of
 * them belongs to the process serving the screen. The screen asks what a
 * reconcile <em>would</em> find - a read - and used to get its answer from a
 * class that also held the applier, which meant the application held the ability
 * to rewrite the catalog in order to answer a question that rewrites nothing.
 * Holding the applier here, where only a worker reaches it, is what removes that
 * rather than excusing it.
 *
 * <p>
 * Deliberately not {@code @Transactional}: the disk walk is minutes of pure I/O
 * over a tree of a hundred thousand files and must never hold a transaction
 * open. Only the writes take one, inside the applier.
 */
@Slf4j
@Service
public class OrganizationReconcileApply {

	private final OrganizationReconcileService organizationReconcileService;
	private final OperationLockService operationLockService;
	private final ReconcileApplier reconcileApplier;

	public OrganizationReconcileApply(OrganizationReconcileService organizationReconcileService,
			OperationLockService operationLockService, ReconcileApplier reconcileApplier) {
		this.organizationReconcileService = organizationReconcileService;
		this.operationLockService = operationLockService;
		this.reconcileApplier = reconcileApplier;
	}

	/**
	 * Scans and writes what the scan found.
	 *
	 * <p>
	 * The tree lock is held for the whole pass so catalog mutations never race an
	 * organization, an undo or a dedup on the same paths. The watcher's blocked
	 * gate already skips that case; the lock closes the check-then-act window it
	 * cannot cover.
	 *
	 * <p>
	 * Declared as RECONCILE rather than ORGANIZATION because the type is what the
	 * refusal message names, and borrowing another operation's type told the user
	 * an organization was running when this pass held the tree.
	 */
	public OrganizationReconcileResponse reconcileAndApply(OrganizationReconcileRequest request) {
		try (var _ = operationLockService.acquire(ExecutionType.RECONCILE, request.source())) {
			Scan scan = organizationReconcileService.scan(request);

			return reconcileApplier.apply(scan);
		} catch (OperationLockException _) {
			// Never reconcile against a moving target: end here without touching the
			// catalog and let the next pass retry - deferred, not dropped.
			log.info("Reconcile deferred on {}: another critical operation is in progress; it will retry next pass.",
					request.source());

			return OrganizationReconcileResponse.deferred(PathUtils.normalize(request.source()));
		}
	}
}