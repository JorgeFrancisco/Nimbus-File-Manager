package br.com.jorgemelo.nimbusfilemanager.inventory.application;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * A transaction manager that manages no resource, for the unit tests that
 * exercise batching and progress without a database behind them.
 *
 * <p>
 * Spring Batch shipped one of these, and removing the framework took it along.
 * It is a handful of empty overrides, which is a better trade than keeping a
 * dependency for one class - and the tests that use it are asserting how many
 * batches were committed, never what a commit did.
 */
class ResourcelessTransactionManager extends AbstractPlatformTransactionManager {

	private static final long serialVersionUID = 1L;

	@Override
	protected Object doGetTransaction() {
		return new Object();
	}

	@Override
	protected void doBegin(Object transaction, TransactionDefinition definition) {
		// Nothing to start: there is no resource to enlist.
	}

	@Override
	protected void doCommit(DefaultTransactionStatus status) {
		// Nothing to commit.
	}

	@Override
	protected void doRollback(DefaultTransactionStatus status) {
		// Nothing to roll back.
	}
}