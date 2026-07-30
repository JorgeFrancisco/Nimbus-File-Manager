package br.com.jorgemelo.nimbusfilemanager.statistics.application;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.ExecutionErrorRepository;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.repository.projection.ErrorFileDetailsResponse;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.projection.ErrorStatisticsResponse;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SizeResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.util.NumberUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PageUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.TextUtils;
import br.com.jorgemelo.nimbusfilemanager.statistics.application.dto.CodecStatisticsResponse;
import br.com.jorgemelo.nimbusfilemanager.statistics.application.dto.ExtensionStatisticsResponse;
import br.com.jorgemelo.nimbusfilemanager.statistics.application.dto.FolderStatisticsResponse;
import br.com.jorgemelo.nimbusfilemanager.statistics.application.dto.StatisticsSummaryResponse;
import br.com.jorgemelo.nimbusfilemanager.statistics.domain.repository.StatisticsRepository;
import br.com.jorgemelo.nimbusfilemanager.statistics.domain.repository.projection.FolderStatisticsRawResponse;
import br.com.jorgemelo.nimbusfilemanager.statistics.domain.repository.projection.StatisticsSummaryRawResponse;

@Service
@Transactional(readOnly = true)
public class StatisticsService {

	private static final String SORT_FILES = "files";

	private final NimbusFileManagerProperties properties;

	private final StatisticsRepository statisticsRepository;
	private final ExecutionErrorRepository executionErrorRepository;
	private final AppSettingService appSettingService;

	@Autowired
	public StatisticsService(StatisticsRepository statisticsRepository,
			ExecutionErrorRepository executionErrorRepository, NimbusFileManagerProperties properties,
			AppSettingService appSettingService) {
		this.statisticsRepository = statisticsRepository;
		this.executionErrorRepository = executionErrorRepository;
		this.properties = properties;
		this.appSettingService = appSettingService;
	}

	public List<ErrorStatisticsResponse> errors() {
		return executionErrorRepository.summarize();
	}

	public List<ErrorStatisticsResponse> errorFiles() {
		return executionErrorRepository.summarizeDistinctFiles();
	}

	public Page<ErrorFileDetailsResponse> errorFileDetails(ExecutionErrorType errorType, String path,
			Pageable pageable) {
		Pageable page = PageUtils.capped(pageable, maxPageSize());

		return executionErrorRepository.findErrorFileDetails(errorType, TextUtils.blankToNull(path), page);
	}

	public StatisticsSummaryResponse summary() {
		StatisticsSummaryRawResponse summary = statisticsRepository.summary();

		return new StatisticsSummaryResponse(summary.totalFiles(), summary.photos(), summary.videos(), summary.audios(),
				summary.documents(), summary.others(), summary.deleted(),

				size(summary.totalSizeBytes()), size(summary.photoSizeBytes()), size(summary.videoSizeBytes()),
				size(summary.audioSizeBytes()), size(summary.documentSizeBytes()), size(summary.otherSizeBytes()));
	}

	public List<CodecStatisticsResponse> codecs() {
		return statisticsRepository.codecs().stream()
				.map(codec -> new CodecStatisticsResponse(codec.codec(), codec.files(), codec.missing(),
						NumberUtils.roundPercentage(codec.percentage()), size(codec.totalSizeBytes())))
				.toList();
	}

	public List<ExtensionStatisticsResponse> extensions(int limit) {
		int normalizedLimit = NumberUtils.limit(limit,
				setting(SettingsConstants.API_DEFAULT_FOLDER_LIMIT, properties.api().defaultFolderLimit()),
				setting(SettingsConstants.API_MAX_FOLDER_LIMIT, properties.api().maxFolderLimit()));

		return statisticsRepository.extensions(PageUtils.firstPage(normalizedLimit)).stream()
				.map(extension -> new ExtensionStatisticsResponse(extension.extension(), extension.fileType(),
						extension.files(), NumberUtils.roundPercentage(extension.percentage()),
						size(extension.totalSizeBytes())))
				.toList();
	}

	public List<FolderStatisticsResponse> folders(int limit, FileType fileType, String codec, String sort) {
		String normalizedCodec = TextUtils.upperBlankToNull(codec);

		String normalizedSort = normalizeSort(sort);

		int normalizedLimit = NumberUtils.limit(limit,
				setting(SettingsConstants.API_DEFAULT_FOLDER_LIMIT, properties.api().defaultFolderLimit()),
				setting(SettingsConstants.API_MAX_FOLDER_LIMIT, properties.api().maxFolderLimit()));

		Pageable page = PageUtils.firstPage(normalizedLimit);

		List<FolderStatisticsRawResponse> folders = SORT_FILES.equals(normalizedSort)
				? statisticsRepository.foldersByFiles(fileType, normalizedCodec, page)
				: statisticsRepository.foldersBySize(fileType, normalizedCodec, page);

		return folders.stream()
				.map(folder -> new FolderStatisticsResponse(folder.folderPath(), folder.files(), folder.photos(),
						folder.videos(), folder.audios(), folder.documents(), folder.others(),
						NumberUtils.roundPercentage(folder.percentage()), size(folder.totalSizeBytes())))
				.toList();
	}

	private String normalizeSort(String sort) {
		if (sort == null || sort.isBlank()) {
			return "size";
		}

		return switch (sort.trim().toLowerCase()) {
		case SORT_FILES -> SORT_FILES;
		case "size" -> "size";
		default -> "size";
		};
	}

	protected SizeResponse size(long bytes) {
		return SizeResponse.of(bytes);
	}

	private int setting(String key, int fallback) {
		return appSettingService.intValue(key, fallback);
	}

	private int maxPageSize() {
		return setting(SettingsConstants.API_MAX_PAGE_SIZE, properties.api().maxPageSize());
	}
}