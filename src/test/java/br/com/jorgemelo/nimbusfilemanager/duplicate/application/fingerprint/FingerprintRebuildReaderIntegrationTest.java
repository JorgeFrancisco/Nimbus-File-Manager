package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintFailureReason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.FingerprintFailure;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.FingerprintRebuildTask;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintFailureRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintRebuildTaskRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PendingPhoto;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

/**
 * Which question the drain asks, and when.
 *
 * <p>
 * Outside a rebuild the queue is what it always was: the files with no
 * fingerprint. During one it cannot be - every file the rebuild owes has a
 * fingerprint, the very one it is going to replace - so the work list becomes
 * the authority and the ordinary question is not asked at all. The two are never
 * merged; exactly one of them answers.
 *
 * <p>
 * On the shared container, because everything here is a property of the queries
 * themselves and nothing commits outside the caller's transaction.
 */
class FingerprintRebuildReaderIntegrationTest extends SharedPostgresIntegrationTest {

	private static final String ALGORITHM = DuplicateConstants.ALGORITHM;

	private static final int MAX_ATTEMPTS = PhashBacklogService.MAX_ATTEMPTS;

	private static final int WHOLE_BATCH = 200;

	@Autowired
	private PhashBacklogService phashBacklogService;

	@Autowired
	private FingerprintRebuildTaskRepository taskRepository;

	@Autowired
	private MediaFingerprintRepository mediaFingerprintRepository;

	@Autowired
	private FingerprintFailureRepository fingerprintFailureRepository;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	/**
	 * The one that matters: a file already fingerprinted is exactly what a rebuild
	 * is for, so the reader has to hand it over rather than skip it.
	 */
	@Test
	void aRebuildOwesAFileThatAlreadyHasAFingerprint() {
		long fileId = catalogued("already-hashed");

		fingerprinted(fileId);
		owed(fileId);

		assertThat(phashBacklogService.fetchPendingBatch(WHOLE_BATCH)).extracting(PendingPhoto::catalogFileId)
				.containsExactly(fileId);
	}

	/** And with no rebuild open, that same file is not pending at all. */
	@Test
	void withoutARebuildAFileThatHasAFingerprintIsNotPending() {
		long fileId = catalogued("already-hashed");

		fingerprinted(fileId);

		assertThat(phashBacklogService.fetchPendingBatch(WHOLE_BATCH)).isEmpty();
		assertThat(phashBacklogService.countPending()).isZero();
	}

	/** Catalog order, so a restart resumes where the batches left off. */
	@Test
	void whatIsOwedComesBackInCatalogOrder() {
		long first = catalogued("a");
		long second = catalogued("b");
		long third = catalogued("c");

		owed(third);
		owed(first);
		owed(second);

		assertThat(phashBacklogService.fetchPendingBatch(WHOLE_BATCH)).extracting(PendingPhoto::catalogFileId)
				.containsExactly(first, second, third);
	}

	/** The batch limit is the batch limit, rebuild or not. */
	@Test
	void aBatchIsNoLongerThanItWasAskedFor() {
		long first = catalogued("a");

		owed(first);
		owed(catalogued("b"));
		owed(catalogued("c"));

		assertThat(phashBacklogService.fetchPendingBatch(2)).hasSize(2);
		assertThat(phashBacklogService.fetchPendingBatch(1)).extracting(PendingPhoto::catalogFileId)
				.containsExactly(first);
	}

	/**
	 * A failure with attempts left is a file that will be tried again, so it stays
	 * in the batch - the task is still owed and nothing has spent it.
	 */
	@Test
	void aFileThatFailedButHasAttemptsLeftIsStillOwed() {
		long fileId = catalogued("retryable");

		owed(fileId);
		failed(fileId, MAX_ATTEMPTS - 1);

		assertThat(phashBacklogService.fetchPendingBatch(WHOLE_BATCH)).extracting(PendingPhoto::catalogFileId)
				.containsExactly(fileId);
	}

	/**
	 * A file that has spent its budget stops being handed back, which is what
	 * makes the drain terminate. Its task is still owed - consuming it belongs to
	 * the transaction that writes the outcome, which is the next slice - so the
	 * count and the batch legitimately disagree here.
	 */
	@Test
	void aFileThatSpentItsAttemptsIsNotHandedBackAgain() {
		long fileId = catalogued("undecodable");

		owed(fileId);
		failed(fileId, MAX_ATTEMPTS);

		assertThat(phashBacklogService.fetchPendingBatch(WHOLE_BATCH)).isEmpty();
		assertThat(taskRepository.countByKindAndAlgorithm(PhashBacklogService.KIND, ALGORITHM))
				.as("still owed until something writes its outcome down").isEqualTo(1);
	}

	/** A file the catalog has lost sight of is not something to point ffmpeg at. */
	@Test
	void aFileThatIsNoLongerActiveIsNotHandedToTheDecoder() {
		long fileId = catalogued("went-missing");

		owed(fileId);

		CatalogFile file = catalogFileRepository.findById(fileId).orElseThrow();

		file.setLifecycleStatus(LifecycleStatus.MISSING);

		catalogFileRepository.saveAndFlush(file);

		assertThat(phashBacklogService.fetchPendingBatch(WHOLE_BATCH)).isEmpty();
		assertThat(taskRepository.countByKindAndAlgorithm(PhashBacklogService.KIND, ALGORITHM))
				.as("and its debt outlives the batch it was left out of").isEqualTo(1);
	}

	/** A file that left the catalog owes nothing, by the foreign key alone. */
	@Test
	void aFileThatLeftTheCatalogIsNotOwedAnyMore() {
		long fileId = catalogued("deleted");

		owed(fileId);

		catalogFileRepository.deleteById(fileId);
		catalogFileRepository.flush();

		assertThat(phashBacklogService.fetchPendingBatch(WHOLE_BATCH)).isEmpty();
		assertThat(taskRepository.countByKindAndAlgorithm(PhashBacklogService.KIND, ALGORITHM)).isZero();
	}

	/** Videos are a different target: a photo rebuild does not hand one over. */
	@Test
	void aPhotoRebuildDoesNotHandOverVideos() {
		long video = cataloguedOfType("a-clip", FileType.VIDEO);

		taskRepository.saveAndFlush(FingerprintRebuildTask.builder().kind(PhashBacklogService.KIND)
				.algorithm(ALGORITHM).catalogFileId(video).seededAt(LocalDateTime.now()).build());

		assertThat(phashBacklogService.fetchPendingBatch(WHOLE_BATCH)).isEmpty();
	}

	/** With a rebuild open, what is pending is what is owed. */
	@Test
	void pendingIsWhatTheListOwesWhileARebuildIsOpen() {
		long fingerprinted = catalogued("hashed");

		fingerprinted(fingerprinted);
		owed(fingerprinted);
		owed(catalogued("bare"));

		assertThat(phashBacklogService.status().pending()).isEqualTo(2);
	}

	/** And without one, it is the count of files that have no fingerprint. */
	@Test
	void pendingIsTheAbsenceOfAFingerprintWhenNoRebuildIsOpen() {
		fingerprinted(catalogued("hashed"));

		catalogued("bare");

		assertThat(phashBacklogService.status().pending()).isEqualTo(1);
	}

	private void owed(long catalogFileId) {
		taskRepository.saveAndFlush(FingerprintRebuildTask.builder().kind(PhashBacklogService.KIND)
				.algorithm(ALGORITHM).catalogFileId(catalogFileId).seededAt(LocalDateTime.now()).build());
	}

	private long catalogued(String name) {
		return cataloguedOfType(name, FileType.PHOTO);
	}

	private long cataloguedOfType(String name, FileType fileType) {
		String key = name + "-" + System.nanoTime();

		String path = "C:/test/" + key + ".jpg";

		CatalogFile file = CatalogFile.builder().extension("jpg").sizeBytes(1L)
				.modifiedAt(Instant.now()).fileType(fileType).lifecycleStatus(LifecycleStatus.ACTIVE).build();
		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(path).currentFolder("C:/test")
				.pathFlavor(PathFlavor.WINDOWS).build());

		return CatalogFiles.catalogued(catalogFileRepository, catalogFileLocationRepository, file).getId();
	}

	private void fingerprinted(long catalogFileId) {
		mediaFingerprintRepository.saveAndFlush(MediaFingerprint.builder().catalogFileId(catalogFileId)
				.kind(PhashBacklogService.KIND).algorithm(ALGORITHM).sampleIndex(0).hashBytes(new byte[32])
				.sampleBytes(new byte[1024]).computedAt(LocalDateTime.now()).build());
	}

	private void failed(long catalogFileId, int attempts) {
		fingerprintFailureRepository.saveAndFlush(FingerprintFailure.builder().catalogFileId(catalogFileId)
				.kind(PhashBacklogService.KIND).algorithm(ALGORITHM).attempts(attempts)
				.reason(FingerprintFailureReason.UNKNOWN).lastError("boom").lastAttemptAt(LocalDateTime.now())
				.build());
	}

}