package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.date.CaptureDateValidator;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.date.MediaDateResolver;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataOptions;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildRequest;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.extractor.MetadataExtractor;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.model.MetadataResult;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MetadataRebuildField;
import br.com.jorgemelo.nimbusfilemanager.shared.application.DateSourceLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaOrientation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.MediaMetadata;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

@ExtendWith(MockitoExtension.class)
class MetadataRebuildServiceTest {

	@TempDir
	Path tempDir;

	@Mock
	private CatalogFileRepository catalogFileRepository;

	@Mock
	private MetadataExtractor metadataExtractor;

	@Mock
	private PlatformTransactionManager transactionManager;

	@Test
	void dryRunShouldOnlyCountCandidateIds() {
		MetadataRebuildRequest request = request(true, List.of(MetadataRebuildField.ALL));

		when(catalogFileRepository.findIdsForMetadataRebuild(any(), any(), eq(null), eq(null), any(), eq(0L),
				any(Pageable.class))).thenReturn(List.of(1L, 2L, 3L));

		var response = service().rebuild(request);

		Assertions.assertThat(response.dryRun()).isTrue();
		Assertions.assertThat(response.candidates()).isEqualTo(3);
		Assertions.assertThat(response.rebuilt()).isZero();
	}

	@Test
	void rebuildShouldProcessSuccessMissingLocationMissingFileAndErrors() throws Exception {
		Path existingFile = Files.writeString(tempDir.resolve("photo.jpg"), "content");
		Path missingFile = tempDir.resolve("missing.jpg");

		CatalogFile success = catalogFile(1L, existingFile);
		CatalogFile withoutLocation = CatalogFile.builder().id(2L).build();
		CatalogFile missing = catalogFile(3L, missingFile);
		CatalogFile error = catalogFile(4L, existingFile);

		MetadataResult metadata = metadata(existingFile);

		MetadataRebuildRequest request = request(false, List.of(MetadataRebuildField.ALL));

		when(catalogFileRepository.findIdsForMetadataRebuild(any(), any(), eq(null), eq(null), any(), eq(0L),
				any(Pageable.class))).thenReturn(List.of(1L, 2L, 3L, 4L));
		when(catalogFileRepository.findIdsForMetadataRebuild(any(), any(), eq(null), eq(null), any(), eq(4L),
				any(Pageable.class))).thenReturn(List.of());
		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L, 2L, 3L, 4L)))
				.thenReturn(List.of(success, withoutLocation, missing, error));
		when(metadataExtractor.extract(existingFile.toAbsolutePath().normalize(), new MetadataOptions(false, true)))
				.thenReturn(metadata).thenThrow(new IllegalStateException("bad metadata"));

		var response = service().rebuild(request);

		Assertions.assertThat(response.dryRun()).isFalse();
		Assertions.assertThat(response.candidates()).isEqualTo(4);
		Assertions.assertThat(response.rebuilt()).isEqualTo(1);
		Assertions.assertThat(response.skippedWithoutLocation()).isEqualTo(1);
		Assertions.assertThat(response.skippedMissing()).isEqualTo(1);
		Assertions.assertThat(response.errors()).isEqualTo(1);
		Assertions.assertThat(success)
				.extracting(CatalogFile::getFileName, CatalogFile::getExtension, CatalogFile::getSizeBytes,
						CatalogFile::getMimeType, CatalogFile::getFileType, CatalogFile::getCreatedAt,
						CatalogFile::getModifiedAt, CatalogFile::getLifecycleStatus, CatalogFile::getAnalysisVersion)
				.containsExactly("photo.jpg", "jpg", 100L, "image/jpeg", FileType.PHOTO,
						LocalDateTime.of(2024, Month.MAY, 9, 10, 30), LocalDateTime.of(2024, Month.MAY, 9, 10, 30),
						LifecycleStatus.ACTIVE, "1");
		Assertions.assertThat(success.getLastAnalysis()).isNotNull();
		Assertions.assertThat(success.getMetadata())
				.extracting(MediaMetadata::getYear, MediaMetadata::getMonth, MediaMetadata::getDay,
						MediaMetadata::getYearMonth, MediaMetadata::getCaptureDate, MediaMetadata::getDateSource)
				.containsExactly(2024, 5, 9, "2024-05", LocalDateTime.of(2024, Month.MAY, 9, 10, 30), DateSource.EXIF);
		Assertions.assertThat(success.getMetadata().getSubcategory()).isEqualTo(MediaSubcategory.CAMERA);

		// One physical transaction boundary was opened for the batch.
		verify(transactionManager).getTransaction(any());
	}

	@Test
	void shouldRefreshOnlyDateWhenRefreshListIsEmpty() throws Exception {
		Path existingFile = Files.writeString(tempDir.resolve("photo.jpg"), "content");

		CatalogFile catalogFile = catalogFile(1L, existingFile);

		MetadataResult metadata = metadata(existingFile);

		MetadataRebuildRequest request = request(false, List.of());

		when(catalogFileRepository.findIdsForMetadataRebuild(any(), any(), eq(null), eq(null), any(), eq(0L),
				any(Pageable.class))).thenReturn(List.of(1L));
		when(catalogFileRepository.findIdsForMetadataRebuild(any(), any(), eq(null), eq(null), any(), eq(1L),
				any(Pageable.class))).thenReturn(List.of());
		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L))).thenReturn(List.of(catalogFile));
		when(metadataExtractor.extract(existingFile.toAbsolutePath().normalize(), new MetadataOptions(false, true)))
				.thenReturn(metadata);

		service().rebuild(request);

		Assertions.assertThat(catalogFile.getFileName()).isNull();
		Assertions.assertThat(catalogFile.getMetadata().getYear()).isEqualTo(2024);
	}

	@Test
	void rebuildShouldRefreshSelectedMediaFieldsWithoutChangingUnselectedDateOrFileFields() throws Exception {
		Path existingFile = Files.writeString(tempDir.resolve("photo.jpg"), "content");

		CatalogFile catalogFile = catalogFile(1L, existingFile);

		MediaMetadata media = catalogFile.getMetadata();

		MetadataResult metadata = metadata(existingFile);

		MetadataRebuildRequest request = request(false,
				List.of(MetadataRebuildField.GPS, MetadataRebuildField.DIMENSIONS, MetadataRebuildField.CAMERA));

		media.setYear(2020);
		media.setYearMonth("2020-01");
		media.setLatitude(1.0);
		media.setLongitude(2.0);
		media.setStoredWidth(10);
		media.setStoredHeight(20);
		media.setDisplayWidth(10);
		media.setDisplayHeight(20);
		media.setManufacturer("Old");
		media.setModel("Old Model");

		prepareSingleRebuild(catalogFile, existingFile, metadata);

		service().rebuild(request);

		Assertions.assertThat(catalogFile.getFileName()).isNull();
		Assertions.assertThat(media.getYear()).isEqualTo(2020);
		Assertions.assertThat(media.getYearMonth()).isEqualTo("2020-01");
		Assertions.assertThat(media.getLatitude()).isEqualTo(-23.5);
		Assertions.assertThat(media.getLongitude()).isEqualTo(-46.6);
		Assertions.assertThat(media.getStoredWidth()).isEqualTo(4000);
		Assertions.assertThat(media.getStoredHeight()).isEqualTo(3000);
		Assertions.assertThat(media.getDisplayWidth()).isEqualTo(4000);
		Assertions.assertThat(media.getDisplayHeight()).isEqualTo(3000);
		Assertions.assertThat(media.getOrientationCode()).isEqualTo(1);
		Assertions.assertThat(media.getRotation()).isZero();
		Assertions.assertThat(media.getOrientationType()).isEqualTo(MediaOrientation.LANDSCAPE);
		Assertions.assertThat(media.getManufacturer()).isEqualTo("Canon");
		Assertions.assertThat(media.getModel()).isEqualTo("R6");
		Assertions.assertThat(media.getMetadataJson()).isEqualTo("{}");
	}

	@Test
	void rebuildShouldNotApplyMediaOnlyFieldsToDocuments() throws Exception {
		Path existingFile = Files.writeString(tempDir.resolve("document.pdf"), "content");

		CatalogFile catalogFile = catalogFile(1L, existingFile);

		MetadataResult metadata = MetadataResult.builder().fileName("document.pdf").extension("pdf").sizeBytes(100L)
				.mimeType("application/pdf").fileType(FileType.PDF).subcategory(MediaSubcategory.UNKNOWN)
				.latitude(-23.5).longitude(-46.6).storedWidth(4000).storedHeight(3000).displayWidth(4000)
				.displayHeight(3000).orientationCode(1).rotation(0).orientationType(MediaOrientation.LANDSCAPE)
				.manufacturer("Canon").model("R6").metadataJson("{}").build();

		MetadataRebuildRequest request = request(false,
				List.of(MetadataRebuildField.GPS, MetadataRebuildField.DIMENSIONS, MetadataRebuildField.CAMERA));

		MediaMetadata media = catalogFile.getMetadata();

		prepareSingleRebuild(catalogFile, existingFile, metadata);

		service().rebuild(request);

		Assertions.assertThat(media.getLatitude()).isNull();
		Assertions.assertThat(media.getLongitude()).isNull();
		Assertions.assertThat(media.getStoredWidth()).isNull();
		Assertions.assertThat(media.getStoredHeight()).isNull();
		Assertions.assertThat(media.getDisplayWidth()).isNull();
		Assertions.assertThat(media.getDisplayHeight()).isNull();
		Assertions.assertThat(media.getManufacturer()).isNull();
		Assertions.assertThat(media.getModel()).isNull();
		Assertions.assertThat(media.getCategory()).isEqualTo(FileType.categoryOf(FileType.PDF));
		Assertions.assertThat(media.getMetadataJson()).isEqualTo("{}");
	}

	@Test
	void rebuildGpsShouldClearExactZeroPairFromExtractedMetadata() throws Exception {
		Path existingFile = Files.writeString(tempDir.resolve("zero.mp4"), "content");

		CatalogFile catalogFile = catalogFile(1L, existingFile);

		catalogFile.getMetadata().setLatitude(-23.0);
		catalogFile.getMetadata().setLongitude(-46.0);

		MetadataResult metadata = MetadataResult.builder().fileName("zero.mp4").extension("mp4").mimeType("video/mp4")
				.fileType(FileType.VIDEO).subcategory(MediaSubcategory.CAMERA).latitude(0.0).longitude(0.0).build();

		prepareSingleRebuild(catalogFile, existingFile, metadata);

		service().rebuild(request(false, List.of(MetadataRebuildField.GPS)));

		Assertions.assertThat(catalogFile.getMetadata().getLatitude()).isNull();
		Assertions.assertThat(catalogFile.getMetadata().getLongitude()).isNull();
	}

	@Test
	void batchRetriesInAFreshTransactionOnOptimisticConflictThenSucceeds() {
		MetadataRebuildRequest request = request(false, List.of(MetadataRebuildField.DATE));

		when(catalogFileRepository.findIdsForMetadataRebuild(any(), any(), eq(null), eq(null), any(), eq(0L),
				any(Pageable.class))).thenReturn(List.of(1L));
		when(catalogFileRepository.findIdsForMetadataRebuild(any(), any(), eq(null), eq(null), any(), eq(1L),
				any(Pageable.class))).thenReturn(List.of());
		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L)))
				.thenThrow(new ObjectOptimisticLockingFailureException(CatalogFile.class, 1L)).thenReturn(List.of());

		service().rebuild(request);

		// Each attempt is its own physical transaction boundary (REQUIRES_NEW): the
		// conflicting attempt rolls back and the retry opens a fresh one, re-reading
		// the candidates. Two boundaries + two reads prove it is not the same tx.
		verify(transactionManager, times(2)).getTransaction(any());
		verify(catalogFileRepository, times(2)).findForMetadataRebuildByIds(List.of(1L));
	}

	/**
	 * The date resolver is exercised for real: it is a pure function of the
	 * extracted metadata, and stubbing it would only assert that the service copies
	 * whatever the stub returned.
	 */
	private MetadataRebuildService service() {
		MediaDateResolver mediaDateResolver = new MediaDateResolver(
				new CaptureDateValidator(Clock.systemDefaultZone()));

		return new MetadataRebuildService(catalogFileRepository, metadataExtractor, mediaDateResolver,
				transactionManager, Clock.systemDefaultZone(), new DateSourceLabels());
	}

	/**
	 * A catalog row created before the media table existed has no metadata yet, so
	 * the rebuild has to attach a fresh one instead of failing on the missing
	 * association.
	 */
	@Test
	void rebuildShouldCreateTheMediaRowWhenTheCatalogFileHasNoneYet() throws Exception {
		Path file = Files.writeString(tempDir.resolve("photo.jpg"), "photo");

		CatalogFile catalogFile = CatalogFile.builder().id(1L).build();

		catalogFile.setLocation(
				CatalogFileLocation.builder().catalogFile(catalogFile).currentPath(file.toString()).build());

		prepareSingleRebuild(catalogFile, file, metadata(file));

		service().rebuild(request(false, List.of(MetadataRebuildField.DATE)));

		Assertions.assertThat(catalogFile.getMetadata()).isNotNull();
		Assertions.assertThat(catalogFile.getMetadata().getSubcategory()).isEqualTo(MediaSubcategory.CAMERA);
	}

	/**
	 * A ZIP package wearing a media extension must not keep content hashes: they
	 * would let it be paired as a visual duplicate of a real image.
	 */
	@Test
	void rebuildShouldClearHashesForAnArchiveMasqueradingAsMedia() throws Exception {
		Path file = Files.writeString(tempDir.resolve("sticker.webp"), "not really a webp");

		CatalogFile catalogFile = catalogFile(1L, file);

		catalogFile.setSha256("sha-before");
		catalogFile.setMd5("md5-before");

		MetadataResult archive = MetadataResult.builder().fileName("sticker.webp").extension("webp").sizeBytes(10L)
				.mimeType("application/zip").fileType(FileType.PHOTO).build();

		prepareSingleRebuild(catalogFile, file, archive);

		service().rebuild(request(false, List.of(MetadataRebuildField.MIME)));

		Assertions.assertThat(catalogFile.getSha256()).isNull();
		Assertions.assertThat(catalogFile.getMd5()).isNull();
	}

	/**
	 * The total feeds the progress bar of the settings panel, so it is capped by
	 * the same limit the rebuild honours - promising more files than it will touch
	 * would leave the bar short of 100%.
	 */
	@Test
	void countCandidatesShouldNotPromiseMoreFilesThanTheLimitAllows() {
		when(catalogFileRepository.countForMetadataRebuild(any(), any(), eq(null), eq(null), any())).thenReturn(4L);

		Assertions.assertThat(service().countCandidates(request(false, List.of(MetadataRebuildField.DATE))))
				.isEqualTo(4L);

		when(catalogFileRepository.countForMetadataRebuild(any(), any(), eq(null), eq(null), any())).thenReturn(500L);

		Assertions.assertThat(service().countCandidates(request(false, List.of(MetadataRebuildField.DATE))))
				.isEqualTo(10L);
	}

	@Test
	void rebuildShouldReportProgressAfterEachBatch() throws Exception {
		Path file = Files.writeString(tempDir.resolve("photo.jpg"), "content");

		CatalogFile catalogFile = catalogFile(1L, file);

		prepareSingleRebuild(catalogFile, file, metadata(file));

		List<Long> reported = new ArrayList<>();

		service().rebuild(request(false, List.of(MetadataRebuildField.DATE)), reported::add);

		Assertions.assertThat(reported).containsExactly(1L);
	}

	private void prepareSingleRebuild(CatalogFile catalogFile, Path existingFile, MetadataResult metadata) {
		when(catalogFileRepository.findIdsForMetadataRebuild(any(), any(), eq(null), eq(null), any(), eq(0L),
				any(Pageable.class))).thenReturn(List.of(1L));
		when(catalogFileRepository.findIdsForMetadataRebuild(any(), any(), eq(null), eq(null), any(), eq(1L),
				any(Pageable.class))).thenReturn(List.of());
		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L))).thenReturn(List.of(catalogFile));
		when(metadataExtractor.extract(existingFile.toAbsolutePath().normalize(), new MetadataOptions(false, true)))
				.thenReturn(metadata);
	}

	private MetadataRebuildRequest request(boolean dryRun, List<MetadataRebuildField> refresh) {
		return new MetadataRebuildRequest(tempDir.toString(), refresh, null, null, 10, dryRun, null);
	}

	private CatalogFile catalogFile(Long id, Path currentPath) {
		CatalogFile catalogFile = CatalogFile.builder().id(id).metadata(MediaMetadata.builder().build()).build();

		CatalogFileLocation location = CatalogFileLocation.builder().catalogFile(catalogFile)
				.currentPath(currentPath.toString()).build();

		catalogFile.setLocation(location);

		return catalogFile;
	}

	private MetadataResult metadata(Path file) {
		LocalDateTime captureDate = LocalDateTime.of(2024, Month.MAY, 9, 10, 30);

		return MetadataResult.builder().fileName(file.getFileName().toString()).extension("jpg").sizeBytes(100L)
				.mimeType("image/jpeg").fileType(FileType.PHOTO).createdAt(captureDate).modifiedAt(captureDate)
				.captureDate(captureDate).dateSource(DateSource.EXIF).subcategory(MediaSubcategory.CAMERA)
				.latitude(-23.5).longitude(-46.6).storedWidth(4000).storedHeight(3000).displayWidth(4000)
				.displayHeight(3000).orientationCode(1).rotation(0).orientationType(MediaOrientation.LANDSCAPE)
				.manufacturer("Canon").model("R6").metadataJson("{}").build();
	}

	/**
	 * The count alone never said how much of the folder the "continue where it
	 * stopped" cutoff was hiding, which is exactly what makes someone stare at a
	 * number and not understand it.
	 */
	@Test
	void dryRunShouldSayHowManyTheContinueCutoffIsHiding() {
		MetadataRebuildRequest request = new MetadataRebuildRequest(tempDir.toString(),
				List.of(MetadataRebuildField.DATE), null, null, 10, true, LocalDateTime.of(2026, Month.JULY, 20, 8, 0));

		when(catalogFileRepository.findIdsForMetadataRebuild(any(), any(), eq(null), eq(null),
				eq(LocalDateTime.of(2026, Month.JULY, 20, 8, 0)), eq(0L), any(Pageable.class))).thenReturn(List.of(1L));
		when(catalogFileRepository.findIdsForMetadataRebuild(any(), any(), eq(null), eq(null),
				eq(MetadataRebuildRequest.NO_CUTOFF), eq(0L), any(Pageable.class))).thenReturn(List.of(1L, 2L, 3L));

		var response = service().rebuild(request);

		Assertions.assertThat(response.candidates()).isEqualTo(1);
		Assertions.assertThat(response.simulation().skippedByCutoff()).isEqualTo(2);
	}

	/**
	 * A simulation that only counts cannot be checked against anything. This one
	 * reports the dates it would write - and writes nothing.
	 */
	@Test
	void dryRunShouldReportWhichDatesWouldChangeWithoutWritingAnything() throws Exception {
		Path file = Files.writeString(tempDir.resolve("clip.jpg"), "conteudo");

		CatalogFile catalogFile = catalogFile(1L, file);

		catalogFile.getMetadata().setCaptureDate(LocalDateTime.of(2026, Month.JULY, 28, 15, 48));
		catalogFile.getMetadata().setDateSource(DateSource.FILE_MODIFIED_AT);

		MetadataRebuildRequest request = request(true, List.of(MetadataRebuildField.DATE));

		when(catalogFileRepository.findIdsForMetadataRebuild(any(), any(), eq(null), eq(null), any(), eq(0L),
				any(Pageable.class))).thenReturn(List.of(1L));
		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L))).thenReturn(List.of(catalogFile));
		when(metadataExtractor.extract(eq(file.toAbsolutePath().normalize()), any())).thenReturn(metadata(file));

		var response = service().rebuild(request);

		Assertions.assertThat(response.simulation().examined()).isEqualTo(1);
		Assertions.assertThat(response.simulation().wouldChange()).isEqualTo(1);
		Assertions.assertThat(response.simulation().preview()).singleElement().satisfies(row -> {
			Assertions.assertThat(row.currentDate()).isEqualTo(LocalDateTime.of(2026, Month.JULY, 28, 15, 48));
			Assertions.assertThat(row.newDate()).isEqualTo(LocalDateTime.of(2024, Month.MAY, 9, 10, 30));
			Assertions.assertThat(row.newSourceLabel()).isNotBlank();
		});
		Assertions.assertThat(catalogFile.getMetadata().getCaptureDate())
				.isEqualTo(LocalDateTime.of(2026, Month.JULY, 28, 15, 48));

		verify(transactionManager, never()).getTransaction(any());
	}

	/** Nothing to rebuild is an answer too, and the screen has to say it. */
	@Test
	void dryRunOverAFolderWithNothingToDoReportsAnEmptySimulation() {
		when(catalogFileRepository.findIdsForMetadataRebuild(any(), any(), eq(null), eq(null), any(), eq(0L),
				any(Pageable.class))).thenReturn(List.of());

		var response = service().rebuild(request(true, List.of(MetadataRebuildField.DATE)));

		Assertions.assertThat(response.candidates()).isZero();
		Assertions.assertThat(response.simulation().examined()).isZero();
		Assertions.assertThat(response.simulation().preview()).isEmpty();
	}

	/** A file no longer on disk cannot be examined, nor counted as if it were. */
	@Test
	void dryRunDoesNotCountAFileThatIsNoLongerOnDisk() {
		CatalogFile catalogFile = catalogFile(1L, tempDir.resolve("sumiu.jpg"));

		when(catalogFileRepository.findIdsForMetadataRebuild(any(), any(), eq(null), eq(null), any(), eq(0L),
				any(Pageable.class))).thenReturn(List.of(1L));
		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L))).thenReturn(List.of(catalogFile));

		var response = service().rebuild(request(true, List.of(MetadataRebuildField.DATE)));

		Assertions.assertThat(response.candidates()).isEqualTo(1);
		Assertions.assertThat(response.simulation().examined()).isZero();
	}

	/**
	 * The preview is about capture dates; simulating a run that does not touch them
	 * counts the files and promises nothing about what would change.
	 */
	@Test
	void dryRunPreviewsNothingWhenTheDateIsNotBeingRebuilt(@TempDir Path folder) throws Exception {
		Path file = Files.writeString(folder.resolve("clip.jpg"), "conteudo");

		CatalogFile catalogFile = catalogFile(1L, file);

		when(catalogFileRepository.findIdsForMetadataRebuild(any(), any(), eq(null), eq(null), any(), eq(0L),
				any(Pageable.class))).thenReturn(List.of(1L));
		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L))).thenReturn(List.of(catalogFile));

		MetadataRebuildRequest request = new MetadataRebuildRequest(folder.toString(),
				List.of(MetadataRebuildField.GPS), null, null, 10, true, null);

		var response = service().rebuild(request);

		Assertions.assertThat(response.simulation().examined()).isEqualTo(1);
		Assertions.assertThat(response.simulation().wouldChange()).isZero();
	}

	/** A file the extractor cannot read leaves the sample, never fails it. */
	@Test
	void dryRunSkipsAFileTheExtractorCannotRead(@TempDir Path folder) throws Exception {
		Path file = Files.writeString(folder.resolve("corrompido.jpg"), "conteudo");

		CatalogFile catalogFile = catalogFile(1L, file);

		when(catalogFileRepository.findIdsForMetadataRebuild(any(), any(), eq(null), eq(null), any(), eq(0L),
				any(Pageable.class))).thenReturn(List.of(1L));
		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L))).thenReturn(List.of(catalogFile));
		when(metadataExtractor.extract(eq(file.toAbsolutePath().normalize()), any()))
				.thenThrow(new IllegalStateException("bad metadata"));

		var response = service().rebuild(new MetadataRebuildRequest(folder.toString(),
				List.of(MetadataRebuildField.DATE), null, null, 10, true, null));

		Assertions.assertThat(response.simulation().examined()).isEqualTo(1);
		Assertions.assertThat(response.simulation().wouldChange()).isZero();
	}

	/** A file whose date would not move is examined and reported as unchanged. */
	@Test
	void dryRunReportsNoChangeWhenTheDateWouldStayTheSame(@TempDir Path folder) throws Exception {
		Path file = Files.writeString(folder.resolve("clip.jpg"), "conteudo");

		CatalogFile catalogFile = catalogFile(1L, file);

		catalogFile.getMetadata().setCaptureDate(LocalDateTime.of(2024, Month.MAY, 9, 10, 30));

		when(catalogFileRepository.findIdsForMetadataRebuild(any(), any(), eq(null), eq(null), any(), eq(0L),
				any(Pageable.class))).thenReturn(List.of(1L));
		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L))).thenReturn(List.of(catalogFile));
		when(metadataExtractor.extract(eq(file.toAbsolutePath().normalize()), any())).thenReturn(metadata(file));

		var response = service().rebuild(new MetadataRebuildRequest(folder.toString(),
				List.of(MetadataRebuildField.DATE), null, null, 10, true, null));

		Assertions.assertThat(response.simulation().examined()).isEqualTo(1);
		Assertions.assertThat(response.simulation().wouldChange()).isZero();
		Assertions.assertThat(response.simulation().preview()).isEmpty();
	}

	/**
	 * A catalog row that never had a media row still previews: the rebuild would
	 * give it one, and the screen shows it as a date appearing from nothing.
	 */
	@Test
	void dryRunPreviewsAFileThatHasNoMediaRowYet(@TempDir Path folder) throws Exception {
		Path file = Files.writeString(folder.resolve("clip.jpg"), "conteudo");

		CatalogFile catalogFile = CatalogFile.builder().id(1L).build();

		catalogFile.setLocation(
				CatalogFileLocation.builder().catalogFile(catalogFile).currentPath(file.toString()).build());

		when(catalogFileRepository.findIdsForMetadataRebuild(any(), any(), eq(null), eq(null), any(), eq(0L),
				any(Pageable.class))).thenReturn(List.of(1L));
		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L))).thenReturn(List.of(catalogFile));
		when(metadataExtractor.extract(eq(file.toAbsolutePath().normalize()), any())).thenReturn(metadata(file));

		var response = service().rebuild(new MetadataRebuildRequest(folder.toString(),
				List.of(MetadataRebuildField.DATE), null, null, 10, true, null));

		Assertions.assertThat(response.simulation().wouldChange()).isEqualTo(1);
		Assertions.assertThat(response.simulation().preview()).singleElement()
				.satisfies(row -> Assertions.assertThat(row.currentDate()).isNull());
	}

	/** A catalog row with no path is skipped instead of breaking the sample. */
	@Test
	void dryRunSkipsARowWithoutAPath() {
		CatalogFile catalogFile = CatalogFile.builder().id(1L).metadata(MediaMetadata.builder().build()).build();

		when(catalogFileRepository.findIdsForMetadataRebuild(any(), any(), eq(null), eq(null), any(), eq(0L),
				any(Pageable.class))).thenReturn(List.of(1L));
		when(catalogFileRepository.findForMetadataRebuildByIds(List.of(1L))).thenReturn(List.of(catalogFile));

		var response = service().rebuild(request(true, List.of(MetadataRebuildField.DATE)));

		Assertions.assertThat(response.simulation().examined()).isZero();
	}
}