package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import java.nio.file.attribute.FileTime;
import java.time.Instant;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentState;
import br.com.jorgemelo.nimbusfilemanager.catalog.infrastructure.persistence.ContentChangeWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * What the catalog can actually remember about a moment in time.
 *
 * <p>
 * A filesystem timestamp is finer than the column that stores it: NTFS counts
 * in hundreds of nanoseconds and {@code TIMESTAMP} keeps microseconds. So a
 * value written and read back is not always the value handed over, and code
 * that compares the two with {@code equals} decides that a file nobody touched
 * has changed. That is not hypothetical - it is what an inventory over an
 * already catalogued library did, on every second pass.
 *
 * <p>
 * These pin the transformation empirically, through the real driver and the
 * real mapping, because the rule the application has to reproduce is whatever
 * this stack does - not what the documentation of any one layer of it says.
 */
class CatalogTimestampPrecisionIntegrationTest extends SharedPostgresIntegrationTest {

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@PersistenceContext
	private EntityManager entityManager;

	@Autowired
	private ContentChangeWriter contentChangeWriter;

	/**
	 * The value observed in production, at the precision the catalog keeps: the
	 * disk said {@code .950657845} and the catalog remembers {@code .950657000}.
	 * Truncated, so nothing has to know how a database or a driver rounds.
	 */
	@Test
	void theValueFromProductionEntersTheDomainTruncatedToTheMicrosecond() {
		Instant fromDisk = Instant.ofEpochSecond(1785694365L, 950657845);

		Assertions.assertThat(CatalogTimestamp.observed(fromDisk))
				.isEqualTo(Instant.ofEpochSecond(1785694365L, 950657000));
	}

	/**
	 * Absent stays absent, in both shapes. Callers hand over what the filesystem
	 * gave them and a filesystem does not always give a creation time; a catalog
	 * date that is unknown has to stay unknown rather than become an epoch.
	 */
	@Test
	void anAbsentMomentIsNotInventedByBeingCanonicalised() {
		Assertions.assertThat(CatalogTimestamp.observed((Instant) null)).isNull();
		Assertions.assertThat(CatalogTimestamp.observed((FileTime) null)).isNull();
	}

	/**
	 * And having entered, it survives the round trip exactly - which is the whole
	 * point: what was written is what comes back, so equality is the right
	 * comparison again.
	 */
	@Test
	void aCanonicalValueSurvivesTheRoundTripUnchanged() {
		Instant canonical = CatalogTimestamp.observed(Instant.ofEpochSecond(1785694365L, 950657845));

		Assertions.assertThat(roundTrip(canonical)).isEqualTo(canonical);
	}

	/**
	 * Every tick a filesystem can produce, canonicalised, comes back as itself.
	 * The ties are the interesting ones: they are where the server and the driver
	 * disagree, and where truncating means neither has anything to decide.
	 */
	@ParameterizedTest
	@CsvSource({ "657000000", "657000100", "657000400", "657000500", "657000600", "657001500",
			"657002500", "657003500", "657999900", "999999900" })
	void noObservableTickIsChangedByBeingStored(int nanos) {
		Instant canonical = CatalogTimestamp.observed(Instant.ofEpochSecond(1785694365L, nanos));

		Assertions.assertThat(roundTrip(canonical)).isEqualTo(canonical);
	}

	/**
	 * The deliberate cost, stated as a test rather than left as a surprise: two
	 * moments closer together than a microsecond are one moment to the catalog.
	 */
	@Test
	void twoMomentsInsideTheSameMicrosecondAreTheSameMomentHere() {
		Instant first = CatalogTimestamp.observed(Instant.ofEpochSecond(1785694365L, 950657000));
		Instant second = CatalogTimestamp.observed(Instant.ofEpochSecond(1785694365L, 950657999));

		Assertions.assertThat(first).isEqualTo(second);
	}

	/** One microsecond apart is a difference the catalog keeps. */
	@Test
	void aMicrosecondApartStaysTwoMoments() {
		Instant first = CatalogTimestamp.observed(Instant.ofEpochSecond(1785694365L, 950657000));
		Instant second = CatalogTimestamp.observed(Instant.ofEpochSecond(1785694365L, 950658000));

		Assertions.assertThat(first).isNotEqualTo(second);
		Assertions.assertThat(roundTrip(first)).isNotEqualTo(roundTrip(second));
	}

	/**
	 * The other writer. {@code modified_at} is written from two places - the JPA
	 * mapping and a native UPDATE in {@code ContentChangeWriter} - and if the two
	 * disagreed about a value the catalog could not hold exactly, the same file
	 * would carry different timestamps depending on which one last touched it.
	 */
	@Test
	void bothWritersAgreeOnWhatTheColumnKeeps() {
		Instant canonical = CatalogTimestamp.observed(Instant.ofEpochSecond(1785694365L, 950657845));

		CatalogFile saved = catalogFileRepository.saveAndFlush(CatalogFile.builder().extension("jpg")
				.sizeBytes(1L).modifiedAt(Instant.ofEpochSecond(1785694365L, 0)).fileType(FileType.PHOTO)
				.lifecycleStatus(LifecycleStatus.ACTIVE).build());

		contentChangeWriter.recordObservedFacts(saved.getId(), saved.getContentRevision(),
				new ContentState(null, 2L, canonical, null));

		entityManager.clear();

		Assertions.assertThat(catalogFileRepository.findById(saved.getId()).orElseThrow().getModifiedAt())
				.as("the native writer keeps it exactly, as the JPA one does").isEqualTo(canonical);
	}

	private Instant roundTrip(Instant modifiedAt) {
		CatalogFile saved = catalogFileRepository.saveAndFlush(CatalogFile.builder().extension("jpg").sizeBytes(1L)
				.modifiedAt(modifiedAt).fileType(FileType.PHOTO).lifecycleStatus(LifecycleStatus.ACTIVE).build());

		catalogFileRepository.flush();

		// Detached deliberately. Without this the answer comes from the persistence
		// context - the very object that was written, nanoseconds and all - and the
		// test proves nothing about what the column can hold.
		entityManager.clear();

		return catalogFileRepository.findById(saved.getId()).orElseThrow().getModifiedAt();
	}
}