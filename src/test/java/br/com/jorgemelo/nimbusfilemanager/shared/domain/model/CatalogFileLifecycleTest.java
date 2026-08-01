package br.com.jorgemelo.nimbusfilemanager.shared.domain.model;

import java.time.LocalDateTime;
import java.time.Month;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;

/**
 * Invariants of the {@code lifecycle_status} state machine: the three
 * mutually-exclusive states and the transitions that must never regress - most
 * importantly, that DELETED is never downgraded to MISSING.
 */
class CatalogFileLifecycleTest {

	@Test
	void builderDefaultsToActive() {
		Assertions.assertThat(CatalogFile.builder().build().getLifecycleStatus()).isEqualTo(LifecycleStatus.ACTIVE);
	}

	@Test
	void statePredicatesAreMutuallyExclusive() {
		CatalogFile active = CatalogFile.builder().lifecycleStatus(LifecycleStatus.ACTIVE).build();
		CatalogFile missing = CatalogFile.builder().lifecycleStatus(LifecycleStatus.MISSING).build();
		CatalogFile deleted = CatalogFile.builder().lifecycleStatus(LifecycleStatus.DELETED).build();

		Assertions.assertThat(active.isActive()).isTrue();
		Assertions.assertThat(active.isMissing()).isFalse();
		Assertions.assertThat(active.isDeleted()).isFalse();

		Assertions.assertThat(missing.isMissing()).isTrue();
		Assertions.assertThat(missing.isActive()).isFalse();

		Assertions.assertThat(deleted.isDeleted()).isTrue();
		Assertions.assertThat(deleted.isActive()).isFalse();
	}

	@Test
	void markActivePromotesMissingAndDeletedBackToActive() {
		CatalogFile missing = CatalogFile.builder().lifecycleStatus(LifecycleStatus.MISSING).build();

		missing.markActive();

		Assertions.assertThat(missing.getLifecycleStatus()).isEqualTo(LifecycleStatus.ACTIVE);

		CatalogFile deleted = CatalogFile.builder().lifecycleStatus(LifecycleStatus.DELETED).build();

		deleted.markActive();

		Assertions.assertThat(deleted.getLifecycleStatus()).isEqualTo(LifecycleStatus.ACTIVE);
	}

	@Test
	void markMissingFromActiveBecomesMissingAndStampsTheChange() {
		CatalogFile file = CatalogFile.builder().lifecycleStatus(LifecycleStatus.ACTIVE).build();

		file.markMissing();

		Assertions.assertThat(file.getLifecycleStatus()).isEqualTo(LifecycleStatus.MISSING);
		Assertions.assertThat(file.getLifecycleChangedAt()).isNotNull();
	}

	@Test
	void markMissingNeverDowngradesDeletedNorStampsIt() {
		CatalogFile deleted = CatalogFile.builder().lifecycleStatus(LifecycleStatus.DELETED).build();

		deleted.markMissing();

		Assertions.assertThat(deleted.getLifecycleStatus()).isEqualTo(LifecycleStatus.DELETED);
		Assertions.assertThat(deleted.getLifecycleChangedAt()).as("no transition, no stamp").isNull();
	}

	@Test
	void markMissingDoesNotResetTheTimestampWhenAlreadyMissing() {
		LocalDateTime firstMark = LocalDateTime.of(2020, Month.JANUARY, 1, 12, 0);

		CatalogFile file = CatalogFile.builder().lifecycleStatus(LifecycleStatus.MISSING).lifecycleChangedAt(firstMark)
				.build();

		file.markMissing();

		Assertions.assertThat(file.getLifecycleChangedAt()).as("retention clock not reset").isEqualTo(firstMark);
	}

	@Test
	void markDeletedWinsFromAnyState() {
		CatalogFile file = CatalogFile.builder().lifecycleStatus(LifecycleStatus.MISSING).build();

		file.markDeleted();

		Assertions.assertThat(file.getLifecycleStatus()).isEqualTo(LifecycleStatus.DELETED);
	}
}