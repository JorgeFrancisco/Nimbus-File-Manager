package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateCandidateGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateFileResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateSummaryResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.GroupParts;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.DuplicateRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateFileRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateFileWithShaRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateGroupRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateSummaryProjection;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.MediaQuality;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SizeResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PageUtils;

@Service
@Transactional(readOnly = true)
public class DuplicateService {

	private final DuplicateRepository duplicateRepository;
	private final AppSettingService appSettingService;
	private final NimbusFileManagerProperties properties;
	private final DuplicateGroupAssembler duplicateGroupAssembler;

	@Autowired
	public DuplicateService(DuplicateRepository duplicateRepository, AppSettingService appSettingService,
			NimbusFileManagerProperties properties, DuplicateGroupAssembler duplicateGroupAssembler) {
		this.duplicateRepository = duplicateRepository;
		this.appSettingService = appSettingService;
		this.properties = properties;
		this.duplicateGroupAssembler = duplicateGroupAssembler;
	}

	public Page<DuplicateGroupResponse> groups(Pageable pageable, Collection<FileType> types) {
		Pageable safePageable = PageUtils.capped(pageable, maxPageSize());

		return duplicateRepository.findDuplicateGroups(resolveTypes(types), safePageable)
				.map(group -> new DuplicateGroupResponse(group.sha256(), group.files(),
						SizeResponse.of(group.totalSizeBytes()), SizeResponse.of(group.wastedSizeBytes())));
	}

	public List<DuplicateFileResponse> files(String sha256) {
		return duplicateRepository.findDuplicateFiles(sha256).stream().map(this::toFileResponse).toList();
	}

	public DuplicateSummaryResponse summary() {
		DuplicateSummaryProjection summary = duplicateRepository.summary();

		return new DuplicateSummaryResponse(summary.getGroups(), summary.getDuplicatedFiles(),
				SizeResponse.of(summary.getTotalSizeBytes()), SizeResponse.of(summary.getWastedSizeBytes()));
	}

	public Page<DuplicateCandidateGroupResponse> candidates(Pageable pageable, Collection<FileType> types) {
		Pageable safePageable = PageUtils.capped(pageable, maxPageSize());

		Set<FileType> fileTypes = resolveTypes(types);

		Page<DuplicateGroupRawResponse> groupsPage = duplicateRepository.findDuplicateGroups(fileTypes, safePageable);

		Map<String, List<DuplicateFileResponse>> filesByGroup = filesByGroup(
				groupsPage.getContent().stream().map(DuplicateGroupRawResponse::sha256).toList(), fileTypes);

		UUID[] allIds = filesByGroup.values().stream().flatMap(List::stream).map(DuplicateFileResponse::id)
				.toArray(UUID[]::new);
		Map<UUID, MediaQuality> quality = duplicateGroupAssembler.qualityByPublicId(allIds);

		return groupsPage.map(group -> {
			List<DuplicateFileResponse> files = filesByGroup.getOrDefault(group.sha256(), List.of());

			GroupParts parts = duplicateGroupAssembler.assemble(files, quality, true);

			return new DuplicateCandidateGroupResponse(group.sha256(), group.files(),
					SizeResponse.of(parts.wastedBytes()), parts.keep(), parts.deleteCandidates(),
					parts.reviewCandidates());
		});
	}

	/**
	 * Loads the files for every group of a page with a single
	 * {@code IN (:sha256List)} query instead of one query per group - a page of 50
	 * groups used to mean 51 round trips to the database.
	 */
	private Map<String, List<DuplicateFileResponse>> filesByGroup(List<String> shas, Collection<FileType> types) {
		if (shas.isEmpty()) {
			return Map.of();
		}

		return duplicateRepository.findDuplicateFilesForShas(shas, types).stream()
				.collect(Collectors.groupingBy(DuplicateFileWithShaRawResponse::sha256, LinkedHashMap::new,
						Collectors.mapping(this::toFileResponse, Collectors.toList())));
	}

	/** Absent/empty selection widens to every FileType (no filter). */
	private Set<FileType> resolveTypes(Collection<FileType> types) {
		return types == null || types.isEmpty() ? EnumSet.allOf(FileType.class) : EnumSet.copyOf(types);
	}

	private DuplicateFileResponse toFileResponse(DuplicateFileRawResponse raw) {
		return new DuplicateFileResponse(raw.id(), raw.fileName(), raw.extension(), raw.fileType(),
				SizeResponse.of(raw.sizeBytes()), raw.currentPath(), raw.currentFolder(), raw.modifiedAt());
	}

	private DuplicateFileResponse toFileResponse(DuplicateFileWithShaRawResponse raw) {
		return new DuplicateFileResponse(raw.id(), raw.fileName(), raw.extension(), raw.fileType(),
				SizeResponse.of(raw.sizeBytes()), raw.currentPath(), raw.currentFolder(), raw.modifiedAt());
	}

	private int maxPageSize() {
		return appSettingService.intValue(SettingsConstants.API_MAX_PAGE_SIZE, properties.api().maxPageSize());
	}
}