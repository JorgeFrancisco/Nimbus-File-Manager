package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

	public static final String TASK_EXECUTOR = "nimbusFileManagerTaskExecutor";

	@Bean(name = TASK_EXECUTOR)
	public Executor nimbusFileManagerTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(4);
		executor.setQueueCapacity(50);
		executor.setThreadNamePrefix("nimbus-file-manager-async-");
		executor.initialize();

		return executor;
	}
}