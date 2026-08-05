package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every way into this service either carries a transaction or is written down
 * as not needing one.
 *
 * <p>
 * The rule exists because of how quietly it broke. Two of the terminal
 * transitions lost their {@code @Transactional} - one annotation had been placed
 * above a Javadoc, where it reads as belonging to whatever comes next - and the
 * result was not a failure. The entity came back detached, the status was set on
 * an object nobody would write, and the step went in anyway under its own
 * transaction. The history said the execution had ended; the row said it was
 * still running; every unit test passed.
 *
 * <p>
 * A test that reads method bodies would be the honest check and is not
 * available. This is the proportionate one: a method here that writes anything
 * needs the boundary, so the default is to require it, and the two that
 * genuinely do not are named with the reason. Adding a transition without a
 * transaction fails here rather than in somebody's queue.
 */
class ExecutionProgressTransactionBoundaryTest {

	/**
	 * The two that write through {@code ExecutionItemProgressWriter} instead, on
	 * purpose: a transactional method called from inside its own class never passes
	 * the proxy, so the write lives in a bean of its own and carries the boundary
	 * there.
	 */
	private static final Set<String> WRITE_SOMEWHERE_ELSE = Set.of("updateCurrentItem", "startsCurrentItem");

	@Test
	void everyPublicOperationDeclaresItsTransactionOrSaysWhyItDoesNotNeedOne() {
		List<String> unbounded = List.of(ExecutionProgressService.class.getDeclaredMethods()).stream()
				.filter(method -> Modifier.isPublic(method.getModifiers())).filter(method -> !method.isSynthetic())
				.filter(method -> !WRITE_SOMEWHERE_ELSE.contains(method.getName()))
				.filter(method -> !method.isAnnotationPresent(Transactional.class)).map(Method::getName).distinct()
				.toList();

		assertThat(unbounded)
				.as("These write to the execution row without a transaction. The entity is detached, so the status"
						+ " change is lost while the step still commits - the row and its history then disagree, and"
						+ " nothing fails. Add @Transactional, or add the method to WRITE_SOMEWHERE_ELSE with the"
						+ " reason it writes elsewhere.")
				.isEmpty();
	}

	/**
	 * And the exceptions stay honest: a name that no longer exists would leave the
	 * list looking like it still covers something.
	 */
	@Test
	void everyNamedExceptionIsStillAMethodOfTheService() {
		List<String> declared = List.of(ExecutionProgressService.class.getDeclaredMethods()).stream()
				.map(Method::getName).distinct().toList();

		assertThat(declared).containsAll(WRITE_SOMEWHERE_ELSE);
	}
}