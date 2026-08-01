package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.PersistedCursor;

/**
 * Runtime checks that the USN cursor round-trips through PostgreSQL: a first
 * save inserts, a second save for the same volume updates in place, and unknown
 * keys read back empty. The 64-bit journal id / USN bit patterns must survive
 * the BIGINT columns unchanged.
 */
@SpringBootTest
@Transactional
@Testcontainers
class UsnCursorStoreIntegrationTest {

	private static final String VOLUME_KEY = "C:/Media";

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	@Autowired
	private UsnCursorStore cursorStore;

	@Test
	void savesLoadsAndUpdatesACursorForAVolume() {
		Assertions.assertThat(cursorStore.load(VOLUME_KEY)).isEmpty();

		cursorStore.save(VOLUME_KEY, 123456789012345L, 5000L);

		Assertions.assertThat(cursorStore.load(VOLUME_KEY)).contains(new PersistedCursor(123456789012345L, 5000L));

		cursorStore.save(VOLUME_KEY, 123456789012345L, 9000L);

		Assertions.assertThat(cursorStore.load(VOLUME_KEY)).contains(new PersistedCursor(123456789012345L, 9000L));
	}

	@Test
	void keepsCursorsOfDifferentVolumesIndependent() {
		cursorStore.save("C:/Media", 1L, 100L);
		cursorStore.save("D:/Photos", 2L, 200L);

		Assertions.assertThat(cursorStore.load("C:/Media")).contains(new PersistedCursor(1L, 100L));
		Assertions.assertThat(cursorStore.load("D:/Photos")).contains(new PersistedCursor(2L, 200L));
	}
}