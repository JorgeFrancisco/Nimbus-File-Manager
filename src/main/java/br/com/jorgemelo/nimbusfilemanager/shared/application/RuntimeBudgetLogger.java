package br.com.jorgemelo.nimbusfilemanager.shared.application;

import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Says, once per start, what this process was actually given.
 *
 * <p>
 * The budgets are decided in three different places - the launcher for an
 * installed application, the application itself when it starts a worker, a Run
 * Configuration in an IDE - so the only way to know which one took effect is to
 * ask the running JVM. A heap that silently fell back to a default is otherwise
 * invisible until something is slow.
 *
 * <p>
 * {@code maxMemory} is the Java heap and nothing else: thread stacks, native
 * buffers, the JVM itself and the ffmpeg and PostgreSQL processes all live
 * outside it. And {@code availableProcessors} is what the JVM sizes its pools
 * by - it is not a share of the machine's CPU.
 */
@Slf4j
@Component
public class RuntimeBudgetLogger {

	private static final long MEGABYTE = 1024L * 1024L;

	private final Environment environment;

	public RuntimeBudgetLogger(Environment environment) {
		this.environment = environment;
	}

	@EventListener(ApplicationReadyEvent.class)
	void logBudget() {
		Runtime runtime = Runtime.getRuntime();

		log.info("Started as {} | max heap {} MB | processors {} | pid {}", roles(), runtime.maxMemory() / MEGABYTE,
				runtime.availableProcessors(), ProcessHandle.current().pid());
	}

	private String roles() {
		List<String> active = List.of(environment.getActiveProfiles());

		return active.isEmpty() ? String.join(",", environment.getDefaultProfiles()) : String.join(",", active);
	}
}