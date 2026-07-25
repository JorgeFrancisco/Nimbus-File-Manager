package br.com.jorgemelo.nimbusfilemanager.organization.application.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationItem;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationSummary;

class OrganizationStatisticsTest {

	private OrganizationItem item(long id, Long sizeBytes, boolean missingDate, boolean samePath, boolean targetExists,
			boolean duplicateTarget) {
		return new OrganizationItem(id, "file" + id, "src", "dst", "2024-01", "01", "IMAGE", "CAMERA", "jpg", "rule",
				"reason", sizeBytes, samePath, missingDate, targetExists, duplicateTarget,
				targetExists || duplicateTarget, null);
	}

	@Test
	void accumulatesCountsSizesAndSummary() {
		List<OrganizationItem> items = List.of(
				// has date, planned move, 100 bytes
				item(1, 100L, false, false, false, false),
				// no date, already organized (same path), 200 bytes
				item(2, 200L, true, true, false, false),
				// has date, planned move, null size (counts as 0), target already exists
				// (conflict)
				item(3, null, false, false, true, false));

		OrganizationStatistics stats = new OrganizationStatistics();

		items.forEach(stats::add);

		OrganizationSummary summary = stats.toSummary(items);

		assertThat(summary.totalFiles()).isEqualTo(3);
		assertThat(summary.filesWithDate()).isEqualTo(2);
		assertThat(summary.filesWithoutDate()).isEqualTo(1);
		assertThat(summary.alreadyOrganized()).isEqualTo(1);
		assertThat(summary.plannedMoves()).isEqualTo(2);
		assertThat(summary.totalSizeBytes()).isEqualTo(300);
		assertThat(summary.conflicts()).isEqualTo(1);
		assertThat(summary.targetAlreadyExists()).isEqualTo(1);
		assertThat(summary.duplicateTargets()).isZero();

		// Direct accessors the executor reads for progress reporting.
		assertThat(stats.totalFiles()).isEqualTo(3);
		assertThat(stats.plannedMoves()).isEqualTo(2);
		assertThat(stats.alreadyOrganized()).isEqualTo(1);
	}

	@Test
	void tracksProcessedCountIndependentlyOfAddedItems() {
		OrganizationStatistics stats = new OrganizationStatistics();

		stats.incrementProcessed();
		stats.incrementProcessed();
		stats.incrementProcessed();

		assertThat(stats.processed()).isEqualTo(3);
	}
}