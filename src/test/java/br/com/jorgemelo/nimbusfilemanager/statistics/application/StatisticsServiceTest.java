package br.com.jorgemelo.nimbusfilemanager.statistics.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.ExecutionErrorRepository;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.projection.ErrorStatisticsResponse;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Api;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Inventory;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;
import br.com.jorgemelo.nimbusfilemanager.statistics.domain.repository.StatisticsRepository;
import br.com.jorgemelo.nimbusfilemanager.statistics.domain.repository.projection.CodecStatisticsRawResponse;
import br.com.jorgemelo.nimbusfilemanager.statistics.domain.repository.projection.ExtensionStatisticsRawResponse;
import br.com.jorgemelo.nimbusfilemanager.statistics.domain.repository.projection.FolderStatisticsRawResponse;
import br.com.jorgemelo.nimbusfilemanager.statistics.domain.repository.projection.StatisticsSummaryRawResponse;

@ExtendWith(MockitoExtension.class)
class StatisticsServiceTest {

	@Mock
	private StatisticsRepository statisticsRepository;

	@Mock
	private ExecutionErrorRepository executionErrorRepository;

	@Mock
	private AppSettingService appSettingService;

	@Test
	void summaryShouldMapRawSizesToReadableResponses() {
		when(statisticsRepository.summary())
				.thenReturn(new StatisticsSummaryRawResponse(10, 4, 3, 1, 1, 1, 0, 2048, 1024, 512, 256, 128, 128));

		var summary = service().summary();

		Assertions.assertThat(summary.totalFiles()).isEqualTo(10);
		Assertions.assertThat(summary.totalSize().formatted()).isEqualTo("2.00 KB");
		Assertions.assertThat(summary.photoSize().bytes()).isEqualTo(1024);
	}

	@Test
	void codecsShouldRoundPercentageAndMapSize() {
		when(statisticsRepository.codecs())
				.thenReturn(List.of(new CodecStatisticsRawResponse("h265", 2, 0, 33.333, 2048)));

		var codecs = service().codecs();

		Assertions.assertThat(codecs).hasSize(1);
		Assertions.assertThat(codecs.getFirst().percentage()).isEqualTo(33.33);
		Assertions.assertThat(codecs.getFirst().totalSize().formatted()).isEqualTo("2.00 KB");
	}

	/**
	 * Files no longer on disk are carried apart, not folded into the count: summing
	 * them said a codec had files the library cannot open, and made the conversion
	 * screen look as if it were hiding work.
	 */
	@Test
	void codecsShouldCarryMissingFilesApartFromThePresentOnes() {
		when(statisticsRepository.codecs()).thenReturn(List.of(new CodecStatisticsRawResponse("h264", 0, 1, 0.0, 0)));

		var codec = service().codecs().getFirst();

		Assertions.assertThat(codec.files()).isZero();
		Assertions.assertThat(codec.missing()).isEqualTo(1);
	}

	@Test
	void extensionsShouldRoundPercentageMapSizeAndApplyLimit() {
		when(statisticsRepository.extensions(PageRequest.of(0, 3)))
				.thenReturn(List.of(new ExtensionStatisticsRawResponse("mp4", FileType.VIDEO, 5, 41.666, 4096),
						new ExtensionStatisticsRawResponse("png", FileType.PHOTO, 3, 25.0, 1024)));

		var extensions = service().extensions(3);

		Assertions.assertThat(extensions).hasSize(2);
		Assertions.assertThat(extensions.getFirst().extension()).isEqualTo("mp4");
		Assertions.assertThat(extensions.getFirst().fileType()).isEqualTo(FileType.VIDEO);
		Assertions.assertThat(extensions.getFirst().percentage()).isEqualTo(41.67);
		Assertions.assertThat(extensions.getFirst().totalSize().formatted()).isEqualTo("4.00 KB");
	}

	@Test
	void foldersShouldNormalizeCodecSortAndLimit() {
		when(statisticsRepository.foldersBySize(FileType.VIDEO, "H265", PageRequest.of(0, 1)))
				.thenReturn(List.of(new FolderStatisticsRawResponse("large", 2, 1, 1, 0, 0, 0, 74.456, 1000)));

		var folders = service().folders(1, FileType.VIDEO, " h265 ", "size");

		Assertions.assertThat(folders).hasSize(1);
		Assertions.assertThat(folders.getFirst().folderPath()).isEqualTo("large");
		Assertions.assertThat(folders.getFirst().percentage()).isEqualTo(74.46);
	}

	@Test
	void foldersShouldSortByFilesWhenRequested() {
		when(statisticsRepository.foldersByFiles(null, null, PageRequest.of(0, 2)))
				.thenReturn(List.of(new FolderStatisticsRawResponse("small", 10, 1, 0, 0, 0, 0, 50, 100),
						new FolderStatisticsRawResponse("large", 2, 1, 0, 0, 0, 0, 50, 1000)));

		var folders = service().folders(0, null, " ", "files");

		Assertions.assertThat(folders).extracting("folderPath").containsExactly("small", "large");
	}

	@Test
	void foldersShouldDefaultInvalidSortToSizeAndApplyConfiguredLimit() {
		when(statisticsRepository.foldersBySize(null, null, PageRequest.of(0, 2)))
				.thenReturn(List.of(new FolderStatisticsRawResponse("large", 2, 1, 0, 0, 0, 0, 50, 1000),
						new FolderStatisticsRawResponse("medium", 5, 1, 0, 0, 0, 0, 50, 500)));

		var folders = service().folders(0, null, null, "unknown");

		Assertions.assertThat(folders).extracting("folderPath").containsExactly("large", "medium");
	}

	@Test
	void foldersShouldDelegateLimitAndOrderingToRepository() {
		when(statisticsRepository.foldersBySize(null, null, PageRequest.of(0, 2)))
				.thenReturn(List.of(new FolderStatisticsRawResponse("large", 2, 1, 0, 0, 0, 0, 50, 1000),
						new FolderStatisticsRawResponse("medium", 5, 1, 0, 0, 0, 0, 50, 500)));

		var folders = service().folders(0, null, null, "size");

		Assertions.assertThat(folders).extracting("folderPath").containsExactly("large", "medium");

		verify(statisticsRepository).foldersBySize(null, null, PageRequest.of(0, 2));
	}

	@Test
	void errorSummariesShouldDelegateToRepositories() {
		List<ErrorStatisticsResponse> errors = List.of(new ErrorStatisticsResponse("UNKNOWN", 2));
		List<ErrorStatisticsResponse> files = List.of(new ErrorStatisticsResponse("METADATA", 1));

		when(executionErrorRepository.summarize()).thenReturn(errors);
		when(executionErrorRepository.summarizeDistinctFiles()).thenReturn(files);

		Assertions.assertThat(service().errors()).isSameAs(errors);
		Assertions.assertThat(service().errorFiles()).isSameAs(files);
	}

	@Test
	void errorQueriesShouldDelegateWithNormalizedPageAndPath() {
		var pageable = PageRequest.of(2, 20, Sort.by("ignored"));

		when(executionErrorRepository.findErrorFileDetails(ExecutionErrorType.UNKNOWN, null, PageRequest.of(2, 20)))
				.thenReturn(Page.empty());

		service().errorFileDetails(ExecutionErrorType.UNKNOWN, "   ", pageable);

		verify(executionErrorRepository).findErrorFileDetails(ExecutionErrorType.UNKNOWN, null, PageRequest.of(2, 20));
	}

	private StatisticsService service() {
		NimbusFileManagerProperties properties = new NimbusFileManagerProperties("C:/workspace", null,
				new Inventory(true, 60_000L), new Api(100, 2, 50), null, null);

		// Mimics an unconfigured AppSettingService (no admin override stored), same as
		// the real
		// intValue(key, fallback) behavior when nothing overrides the property defaults
		// above.
		lenient().when(appSettingService.intValue(any(), any(Integer.class)))
				.thenAnswer(invocation -> invocation.getArgument(1));

		return new StatisticsService(statisticsRepository, executionErrorRepository, properties, appSettingService);
	}
}