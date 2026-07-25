package br.com.jorgemelo.nimbusfilemanager.media.domain.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.media.domain.repository.projection.MediaSearchFilter;
import br.com.jorgemelo.nimbusfilemanager.media.domain.repository.projection.MediaSearchRawResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.MediaMetadata;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Video;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.FilterBuilder;

/**
 * Runtime checks that {@code MediaSearchRepository.search} binds its
 * {@link MediaSearchFilter} through the SpEL placeholders ({@code :#{#filter.x}})
 * against a real PostgreSQL instance. Every filter field is exercised in both a
 * matching and a non-matching value so the {@code (:x IS NULL OR ...)} branches
 * are proven to fire at runtime, not just compile.
 */
@SpringBootTest
@Transactional
@Testcontainers
class MediaSearchRepositorySpelIntegrationTest {

	private static final Pageable PAGE = PageRequest.of(0, 10);

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private MediaSearchRepository mediaSearchRepository;

	private CatalogFile persisted;

	@BeforeEach
	void seed() {
		String key = "search-spel-" + System.nanoTime();
		String path = "C:/Vacation/movie-" + System.nanoTime() + ".mp4";

		CatalogFile file = CatalogFile.builder().fileKey(key).fileName("movie.mp4").extension("mp4").sizeBytes(2048L)
				.modifiedAt(LocalDateTime.now()).fileType(FileType.VIDEO).build();

		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(path).currentFolder("C:/Vacation")
				.originalPath(path).originalFolder("C:/Vacation").build());

		file.setMetadata(MediaMetadata.builder().catalogFile(file).category(FileCategory.MEDIA)
				.subcategory(MediaSubcategory.CAMERA).year(2024).month(5).build());

		file.setVideo(Video.builder().catalogFile(file).videoCodec("h265").build());

		persisted = catalogFileRepository.saveAndFlush(file);
	}

	@Test
	void allNullFilterMatchesEveryRow() {
		Assertions.assertThat(search(filter().build())).anyMatch(this::isSeeded);
	}

	@Test
	void fileTypeBindsAsEnum() {
		Assertions.assertThat(search(filter().fileType(FileType.VIDEO).build())).anyMatch(this::isSeeded);
		Assertions.assertThat(search(filter().fileType(FileType.PHOTO).build())).noneMatch(this::isSeeded);
	}

	@Test
	void codecIsComparedUppercasedAndTrimmed() {
		Assertions.assertThat(search(filter().codec("H265").build())).anyMatch(this::isSeeded);
		Assertions.assertThat(search(filter().codec("H264").build())).noneMatch(this::isSeeded);
	}

	@Test
	void folderUsesCaseInsensitiveLike() {
		Assertions.assertThat(search(filter().folder("vaca").build())).anyMatch(this::isSeeded);
		Assertions.assertThat(search(filter().folder("nope").build())).noneMatch(this::isSeeded);
	}

	@Test
	void extensionIsCaseInsensitive() {
		Assertions.assertThat(search(filter().extension("MP4").build())).anyMatch(this::isSeeded);
		Assertions.assertThat(search(filter().extension("jpg").build())).noneMatch(this::isSeeded);
	}

	@Test
	void yearAndMonthBindAsIntegers() {
		Assertions.assertThat(search(filter().year(2024).month(5).build())).anyMatch(this::isSeeded);
		Assertions.assertThat(search(filter().year(1999).build())).noneMatch(this::isSeeded);
		Assertions.assertThat(search(filter().month(12).build())).noneMatch(this::isSeeded);
	}

	@Test
	void sizeBoundsBindAsLongs() {
		Assertions.assertThat(search(filter().minSizeBytes(1000L).maxSizeBytes(5000L).build())).anyMatch(this::isSeeded);
		Assertions.assertThat(search(filter().minSizeBytes(5000L).build())).noneMatch(this::isSeeded);
		Assertions.assertThat(search(filter().maxSizeBytes(1000L).build())).noneMatch(this::isSeeded);
	}

	private List<MediaSearchRawResponse> search(MediaSearchFilter filter) {
		return mediaSearchRepository.search(filter, PAGE).getContent();
	}

	private boolean isSeeded(MediaSearchRawResponse row) {
		return persisted.getPublicId().equals(row.id());
	}

	private FilterBuilder filter() {
		return new FilterBuilder();
	}
}