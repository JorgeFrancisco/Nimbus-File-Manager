package br.com.jorgemelo.nimbusfilemanager.statistics.infrastructure.rest;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.projection.ErrorStatisticsResponse;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.repository.projection.ErrorFileDetailsResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PagedResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.statistics.application.StatisticsService;
import br.com.jorgemelo.nimbusfilemanager.statistics.application.dto.CodecStatisticsResponse;
import br.com.jorgemelo.nimbusfilemanager.statistics.application.dto.ExtensionStatisticsResponse;
import br.com.jorgemelo.nimbusfilemanager.statistics.application.dto.FolderStatisticsResponse;
import br.com.jorgemelo.nimbusfilemanager.statistics.application.dto.StatisticsSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/api/statistics")
public class StatisticsController {

	private final StatisticsService statisticsService;

	public StatisticsController(StatisticsService statisticsService) {
		this.statisticsService = statisticsService;
	}

	@GetMapping
	@Operation(summary = "Returns the overall media statistics")
	public StatisticsSummaryResponse summary() {
		return statisticsService.summary();
	}

	@GetMapping("/codecs")
	@Operation(summary = "Returns video codec statistics")
	public List<CodecStatisticsResponse> codecs() {
		return statisticsService.codecs();
	}

	@GetMapping("/extensions")
	@Operation(summary = "Returns file counts and sizes grouped by extension (mp4, png, jpg...)")
	public List<ExtensionStatisticsResponse> extensions(@RequestParam(defaultValue = "50") int limit) {
		return statisticsService.extensions(limit);
	}

	@GetMapping("/folders")
	@Operation(summary = "Returns folder statistics")
	public List<FolderStatisticsResponse> folders(@RequestParam(defaultValue = "20") int limit,
			@RequestParam(required = false) FileType fileType, @RequestParam(required = false) String codec,
			@RequestParam(defaultValue = "size") String sort) {
		return statisticsService.folders(limit, fileType, codec, sort);
	}

	@GetMapping("/errors")
	@Operation(summary = "Returns error occurrence statistics")
	public List<ErrorStatisticsResponse> errors() {
		return statisticsService.errors();
	}

	@GetMapping("/errors/files")
	@Operation(summary = "Returns unique files with error statistics")
	public List<ErrorStatisticsResponse> errorFiles() {
		return statisticsService.errorFiles();
	}

	@GetMapping("/errors/files/details")
	public PagedResponse<ErrorFileDetailsResponse> errorFileDetails(
			@RequestParam(required = false) ExecutionErrorType errorType, @RequestParam(required = false) String path,
			Pageable pageable) {
		return PagedResponse.from(statisticsService.errorFileDetails(errorType, path, pageable));
	}
}