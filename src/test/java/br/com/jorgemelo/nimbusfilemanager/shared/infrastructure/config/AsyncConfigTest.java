package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * One pool, and it is the last one. The visual analysis pool went with the
 * fingerprint backlogs and the geolocation pool with the location rebuild and
 * the dataset update - a pool nobody submits to is a thread budget spent on
 * nothing. What is left serves the three runners that stay in the application by
 * decision.
 */
class AsyncConfigTest {

	@Test
	void configuresTheOnePoolTheApplicationStillSubmitsTo() {
		AsyncConfig config = new AsyncConfig();

		ThreadPoolTaskExecutor operational = (ThreadPoolTaskExecutor) config.nimbusFileManagerTaskExecutor();

		try {
			assertThat(operational.getCorePoolSize()).isEqualTo(2);
			assertThat(operational.getMaxPoolSize()).isEqualTo(4);
			assertThat(operational.getThreadNamePrefix()).isEqualTo("nimbus-file-manager-async-");
		} finally {
			operational.shutdown();
		}
	}
}