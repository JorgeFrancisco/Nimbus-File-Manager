package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import java.util.concurrent.atomic.AtomicInteger;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class OptimisticLockRetryTest {

	@Test
	void runsOnceWhenTheAttemptSucceeds() {
		AtomicInteger attempts = new AtomicInteger();

		OptimisticLockRetry.run("op", 3, attempts::incrementAndGet);

		Assertions.assertThat(attempts.get()).isEqualTo(1);
	}

	@Test
	void retriesOnConflictAndSucceedsWithinTheBound() {
		AtomicInteger attempts = new AtomicInteger();

		OptimisticLockRetry.run("op", 3, () -> {
			if (attempts.incrementAndGet() < 3) {
				throw new ObjectOptimisticLockingFailureException(Object.class, 1L);
			}
		});

		Assertions.assertThat(attempts.get()).isEqualTo(3);
	}

	@Test
	void propagatesTheConflictAfterExhaustingTheBoundWithoutInfiniteRetry() {
		AtomicInteger attempts = new AtomicInteger();

		Assertions.assertThatThrownBy(() -> OptimisticLockRetry.run("op", 2, () -> {
			attempts.incrementAndGet();
			throw new ObjectOptimisticLockingFailureException(Object.class, 1L);
		})).isInstanceOf(ObjectOptimisticLockingFailureException.class);

		Assertions.assertThat(attempts.get()).isEqualTo(2);
	}

	@Test
	void doesNotRetryNonOptimisticExceptions() {
		AtomicInteger attempts = new AtomicInteger();

		Assertions.assertThatThrownBy(() -> OptimisticLockRetry.run("op", 3, () -> {
			attempts.incrementAndGet();
			throw new IllegalStateException("boom");
		})).isInstanceOf(IllegalStateException.class);

		Assertions.assertThat(attempts.get()).isEqualTo(1);
	}
}