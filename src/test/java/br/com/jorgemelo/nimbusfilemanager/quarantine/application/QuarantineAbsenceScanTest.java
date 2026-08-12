package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Which quarantine records have no file behind them - a reading, and only a
 * reading. What it produces is a shortlist to queue, never a verdict: the
 * worker looks again under the lock before anything is removed.
 */
class QuarantineAbsenceScanTest {

	private final MovementRepository movementRepository = mock(MovementRepository.class);
	private final QuarantineAbsenceScan scan = new QuarantineAbsenceScan(movementRepository);

	@Test
	void namesOnlyTheRecordsWhoseFileIsNotThere(@TempDir Path tmp) throws Exception {
		Path folder = Files.createDirectories(tmp.resolve("trash").resolve("exec-1"));
		Path present = Files.writeString(folder.resolve("11__present.jpg"), "here");

		Movement kept = quarantined(present);
		Movement absent = quarantined(folder.resolve("10__gone.jpg"));

		when(movementRepository.findByStatusAndReasonInOrderByIdDesc(eq(MovementStatus.MOVED),
				eq(QuarantineConstants.QUARANTINED_REASONS), any()))
						.thenReturn(new PageImpl<>(List.of(kept, absent)));

		Assertions.assertThat(scan.absent()).containsExactly(absent.getMovementPublicId());
	}

	/**
	 * The reading is bounded by the same cap as the pass that does the work, or the
	 * two would silently disagree about how much a run is.
	 */
	@Test
	void readsAtMostOneRunWorthOfRecords() {
		when(movementRepository.findByStatusAndReasonInOrderByIdDesc(eq(MovementStatus.MOVED),
				eq(QuarantineConstants.QUARANTINED_REASONS), any())).thenReturn(new PageImpl<>(List.of()));

		Assertions.assertThat(scan.absent()).isEmpty();

		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.captor();

		verify(movementRepository).findByStatusAndReasonInOrderByIdDesc(eq(MovementStatus.MOVED),
				eq(QuarantineConstants.QUARANTINED_REASONS), pageable.capture());

		Assertions.assertThat(pageable.getValue().getPageSize()).isEqualTo(QuarantineConstants.MAX_PER_RUN);
	}

	private Movement quarantined(Path quarantine) {
		return Movement.builder().movementPublicId(UUID.randomUUID()).requestedSourcePath("ignored")
				.requestedTargetPath(PathUtils.normalize(quarantine)).status(MovementStatus.MOVED)
				.reason(MovementReason.DUPLICATE_QUARANTINED).movedAt(Instant.now()).build();
	}
}