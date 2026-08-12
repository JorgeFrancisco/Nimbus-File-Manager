package br.com.jorgemelo.nimbusfilemanager.shared.domain.model;

import java.time.Instant;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;

/**
 * Invariants of the {@code lifecycle_status} state machine: the three
 * mutually-exclusive states, and the two transitions the entity is still
 * allowed to make on its own.
 *
 * <p>
 * Going missing is not one of them, and its absence here is the point. Becoming
 * active or removed happens because an operation did something to the file, and
 * the entity changes with it in the operation's transaction. A file going
 * missing is something a pass concluded from an empty path - it has provenance,
 * a moment and an observer - so it is written through the door that records the
 * fact alongside the column, and an entity method for it would be a second way
 * to reach the same state carrying none of that.
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
	void markDeletedWinsFromAnyState() {
		CatalogFile file = CatalogFile.builder().lifecycleStatus(LifecycleStatus.MISSING).build();

		file.markDeleted();

		Assertions.assertThat(file.getLifecycleStatus()).isEqualTo(LifecycleStatus.DELETED);
	}

	/**
	 * The stamp is when the state changed, so a transition that does not happen
	 * does not touch it - which is what keeps a retention clock from being reset by
	 * an operation that concluded nothing new.
	 */
	@Test
	void aStateThatDidNotChangeDoesNotMoveTheStamp() {
		Instant firstMark = Instant.parse("2020-01-01T12:00:00Z");

		CatalogFile deleted = CatalogFile.builder().lifecycleStatus(LifecycleStatus.DELETED)
				.lifecycleChangedAt(firstMark).build();

		deleted.markDeleted();

		Assertions.assertThat(deleted.getLifecycleChangedAt()).as("retention clock not reset").isEqualTo(firstMark);
	}

	@Test
	void aStateThatChangedIsStamped() {
		CatalogFile file = CatalogFile.builder().lifecycleStatus(LifecycleStatus.ACTIVE).build();

		file.markDeleted();

		Assertions.assertThat(file.getLifecycleChangedAt()).isNotNull();
	}
}