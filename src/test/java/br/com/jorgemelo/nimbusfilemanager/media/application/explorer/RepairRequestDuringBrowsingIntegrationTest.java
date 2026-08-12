package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationPathKey;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.InventoryBatchTestSeeder;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryRequest;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.FileExplorerView;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;

/**
 * What listing a folder does about a catalogued file that is no longer there.
 *
 * <p>
 * Browsing is the one moment this product looks somewhere the watcher never
 * does, so what it finds there is worth acting on - and acting is a request,
 * asked for from inside a transaction that was opened read-only because listing
 * is a query. PostgreSQL refuses an insert under one, which is the whole reason
 * the launcher opens a transaction of its own.
 *
 * <p>
 * <b>Rollback is deliberately not proven here, because it is deliberately not
 * the behaviour.</b> Listing writes nothing, so there is no state a rollback
 * could undo; and what is being reported - a catalogued file absent from the
 * disk - is a fact about the filesystem rather than about this transaction. It
 * stays true whether or not the listing that noticed it finishes, so the repair
 * it asks for is right to survive on its own. Tying the two together would buy
 * nothing and would cost a read-write transaction held open across the whole
 * directory walk, on the thread answering a page request.
 *
 * <p>
 * Against a real PostgreSQL and with no test transaction wrapped around it: the
 * subject is what one transaction is allowed to do inside another, and a test
 * that opened a read-write one of its own would have the boundary under test
 * quietly join it - proving nothing, and passing either way.
 */
@SpringBootTest
@Testcontainers
@Import(InventoryBatchTestSeeder.class)
// The fixture is a real inventory pass, which takes the global operation lock.
@ResourceLock("inventory-batch")
class RepairRequestDuringBrowsingIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

	@Autowired
	private FileExplorerService fileExplorerService;

	@Autowired
	private ExplorerReconcileLauncher reconcileLauncher;

	@Autowired
	private ExecutionEnqueueService executionEnqueueService;

	@Autowired
	private InventoryBatchTestSeeder inventorySeeder;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/** Nothing missing, nothing asked for - a listing is not a reason by itself. */
	@Test
	void aFolderWhereNothingIsMissingAsksForNoRepair(@TempDir Path folder) throws IOException {
		Files.writeString(folder.resolve("present.jpg"), "catalogued and still there");

		inventorySeeder.seed(inventory(folder));

		FileExplorerView view = fileExplorerService.browse(folder.toString(), "details");

		Assertions.assertThat(view.missingCount()).isZero();
		Assertions.assertThat(reconcilesFor(folder)).isEmpty();
	}

	/**
	 * The reproduction. The call goes through the bean, so the class-level
	 * {@code readOnly = true} is really in force - which is what makes this fail
	 * with SQLState 25006 the moment the launcher stops opening a transaction of
	 * its own.
	 */
	@Test
	void aCataloguedFileMissingFromDiskIsReportedForRepair(@TempDir Path folder) throws IOException {
		Path place = Files.writeString(folder.resolve("gone.jpg"), "catalogued, then removed");

		inventorySeeder.seed(inventory(folder));

		Files.delete(place);

		FileExplorerView view = fileExplorerService.browse(folder.toString(), "details");

		Assertions.assertThat(view.missingCount()).as("the listing noticed it").isEqualTo(1);
		Assertions.assertThat(reconcilesFor(folder)).as("and asked for exactly one repair").hasSize(1);
	}

	/**
	 * The same request, from the two boundaries, with opposite outcomes - which is
	 * the reason the launcher exists stated as a test rather than as a comment.
	 *
	 * <p>
	 * Asked for directly, the insert joins the read-only transaction and the
	 * database refuses it. Asked for through the launcher, from inside that same
	 * transaction, it is written. Nothing differs between the two but the
	 * propagation.
	 */
	@Test
	void theRequestIsWrittenFromATransactionOfItsOwnBecauseTheCallersRefusesIt(@TempDir Path folder) {
		TransactionTemplate browsing = readOnlyTransaction();

		Throwable refused = Assertions
				.catchThrowable(() -> browsing.execute(_ -> executionEnqueueService.enqueue(repairOf(folder))));

		Assertions.assertThat(sqlStateOf(refused)).as("cannot execute INSERT in a read-only transaction")
				.isEqualTo("25006");
		Assertions.assertThat(reconcilesFor(folder)).as("and nothing was left behind by the refusal").isEmpty();

		browsing.executeWithoutResult(_ -> reconcileLauncher.repairFolder(folder));

		Assertions.assertThat(reconcilesFor(folder)).as("the launcher's own transaction wrote it").hasSize(1);
	}

	/**
	 * A folder is looked at again a few seconds later, as a screen refresh does.
	 * The queue's deduplication is the authority and stays it: the request already
	 * waiting is the answer, not the start of a pile.
	 */
	@Test
	void listingTheSameFolderAgainDoesNotPileUpRequests(@TempDir Path folder) throws IOException {
		Path place = Files.writeString(folder.resolve("gone.jpg"), "catalogued, then removed");

		inventorySeeder.seed(inventory(folder));

		Files.delete(place);

		fileExplorerService.browse(folder.toString(), "details");
		fileExplorerService.browse(folder.toString(), "details");

		Assertions.assertThat(reconcilesFor(folder)).hasSize(1);
	}

	private InventoryRequest inventory(Path folder) {
		return new InventoryRequest(folder.toString(), true, false, false, false);
	}

	private TransactionTemplate readOnlyTransaction() {
		TransactionTemplate template = new TransactionTemplate(transactionManager);

		template.setReadOnly(true);

		return template;
	}

	/**
	 * Deliberately the request the launcher builds, so that the propagation is the
	 * only thing the two paths do not have in common.
	 */
	private Execution repairOf(Path folder) {
		return Execution.builder().executionType(ExecutionType.RECONCILE).triggerEvent(ExecutionTrigger.FILE_EVENT)
				.sourcePath(folder.toString()).recursive(false).executeFlag(true)
				.dedupKey(OperationPathKey.canonical(folder))
				.statusMessage(StatusMessage.code(ExecutionMessages.RECONCILE_REPAIRED)).build();
	}

	/**
	 * By deduplication key rather than by path: that is the identity the queue
	 * itself uses, and reading by anything else would answer a question the queue
	 * is not asking.
	 */
	private List<Long> reconcilesFor(Path folder) {
		return jdbcTemplate.queryForList("SELECT id FROM execution WHERE execution_type = ? AND dedup_key = ?",
				Long.class, ExecutionType.RECONCILE.name(), OperationPathKey.canonical(folder));
	}

	private String sqlStateOf(Throwable thrown) {
		for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException sql) {
				return sql.getSQLState();
			}
		}

		return null;
	}
}