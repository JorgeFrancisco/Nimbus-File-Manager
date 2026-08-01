package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Collection;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateDeletionAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.PhotoSimilarityAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.PhashBacklogAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.GeoDatasetAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.LocationRebuildAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationRebuildScope;

class AsyncConfigTest {

	@Test
	void configuresThreeIndependentExecutorPools() {
		AsyncConfig config = new AsyncConfig();

		ThreadPoolTaskExecutor operational = (ThreadPoolTaskExecutor) config.nimbusFileManagerTaskExecutor();
		ThreadPoolTaskExecutor geolocation = (ThreadPoolTaskExecutor) config.nimbusFileManagerGeolocationExecutor();
		ThreadPoolTaskExecutor visualAnalysis = (ThreadPoolTaskExecutor) config
				.nimbusFileManagerVisualAnalysisExecutor();

		try {
			assertThat(operational.getCorePoolSize()).isEqualTo(2);
			assertThat(operational.getMaxPoolSize()).isEqualTo(4);
			assertThat(operational.getThreadNamePrefix()).isEqualTo("nimbus-file-manager-async-");

			assertThat(geolocation.getCorePoolSize()).isEqualTo(2);
			assertThat(geolocation.getMaxPoolSize()).isEqualTo(2);
			assertThat(geolocation.getThreadNamePrefix()).isEqualTo("nimbus-file-manager-geolocation-");

			assertThat(visualAnalysis.getCorePoolSize()).isEqualTo(2);
			assertThat(visualAnalysis.getMaxPoolSize()).isEqualTo(2);
			assertThat(visualAnalysis.getThreadNamePrefix()).isEqualTo("nimbus-file-manager-visual-analysis-");
		} finally {
			operational.shutdown();
			geolocation.shutdown();
			visualAnalysis.shutdown();
		}
	}

	@Test
	void routesLongRunningJobsToTheirDedicatedPools() throws NoSuchMethodException {
		assertExecutor(GeoDatasetAsyncRunner.class.getDeclaredMethod("downloadAndImport"),
				AsyncConfig.GEOLOCATION_EXECUTOR);
		assertExecutor(LocationRebuildAsyncRunner.class.getDeclaredMethod("rebuild", LocationRebuildScope.class),
				AsyncConfig.GEOLOCATION_EXECUTOR);
		assertExecutor(PhashBacklogAsyncRunner.class.getDeclaredMethod("run"), AsyncConfig.VISUAL_ANALYSIS_EXECUTOR);
		assertExecutor(PhotoSimilarityAsyncRunner.class.getDeclaredMethod("run", int.class),
				AsyncConfig.VISUAL_ANALYSIS_EXECUTOR);
		assertExecutor(DuplicateDeletionAsyncRunner.class.getDeclaredMethod("run", Collection.class),
				AsyncConfig.TASK_EXECUTOR);
	}

	private void assertExecutor(Method method, String executorName) {
		assertThat(method.getAnnotation(Async.class)).isNotNull().extracting(Async::value).isEqualTo(executorName);
	}
}