package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.Decision;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateCandidateFileResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateFileResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.GroupParts;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.Signals;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Verdict;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaQualityRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.MediaQuality;

/**
 * Shared assembly of a duplicate/similar group: loads media-quality signals,
 * applies {@link DuplicateKeepPolicy} and partitions the files into keep /
 * delete-candidate / review-candidate views. Both the exact-duplicate and
 * similar-photo services delegate here so the keep-policy wiring and the group
 * partitioning live in a single place.
 */
@Component
public class DuplicateGroupAssembler {

	private final DuplicateKeepPolicy duplicateKeepPolicy;
	private final MediaQualityRepository mediaQualityRepository;

	public DuplicateGroupAssembler(DuplicateKeepPolicy duplicateKeepPolicy,
			MediaQualityRepository mediaQualityRepository) {
		this.duplicateKeepPolicy = duplicateKeepPolicy;
		this.mediaQualityRepository = mediaQualityRepository;
	}

	/**
	 * Media-quality signals for the given public ids, keyed by id (empty when
	 * none).
	 */
	Map<UUID, MediaQuality> qualityByPublicId(List<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}

		return mediaQualityRepository.findByPublicIdIn(ids).stream()
				.collect(Collectors.toMap(MediaQuality::publicId, q -> q, (first, _) -> first));
	}

	/**
	 * Applies the keep policy and partitions {@code files} into the kept file, the
	 * deletion candidates and the review candidates, plus the recoverable bytes.
	 *
	 * @param exactGroup {@code true} for byte-identical (SHA-256) groups,
	 * {@code false} for similar-photo groups
	 */
	GroupParts assemble(List<DuplicateFileResponse> files, Map<UUID, MediaQuality> quality, boolean exactGroup) {
		List<Signals> signals = files.stream().map(file -> toSignals(file, quality.get(file.id()))).toList();

		Map<UUID, Decision> decisions = duplicateKeepPolicy.decide(signals, exactGroup);

		DuplicateFileResponse keepFile = files.stream()
				.filter(f -> decisions.containsKey(f.id()) && decisions.get(f.id()).verdict() == Verdict.KEEP)
				.findFirst().orElse(files.isEmpty() ? null : files.getFirst());

		if (keepFile == null) {
			throw new IllegalStateException("Group does not contain files.");
		}

		List<DuplicateFileResponse> deleteCandidates = files.stream().filter(f -> !f.id().equals(keepFile.id())
				&& decisions.containsKey(f.id()) && decisions.get(f.id()).verdict() == Verdict.DELETE_CANDIDATE)
				.toList();

		List<DuplicateFileResponse> reviewCandidates = files.stream().filter(f -> !f.id().equals(keepFile.id())
				&& decisions.containsKey(f.id()) && decisions.get(f.id()).verdict() == Verdict.REVIEW).toList();

		long wastedBytes = deleteCandidates.stream().mapToLong(file -> file.size().bytes()).sum();

		return new GroupParts(toCandidateFile(keepFile, decisions.get(keepFile.id()), quality.get(keepFile.id())),
				deleteCandidates.stream().map(f -> toCandidateFile(f, decisions.get(f.id()), quality.get(f.id())))
						.toList(),
				reviewCandidates.stream().map(f -> toCandidateFile(f, decisions.get(f.id()), quality.get(f.id())))
						.toList(),
				wastedBytes);
	}

	private Signals toSignals(DuplicateFileResponse file, MediaQuality quality) {
		return new Signals(file.id(), quality != null && quality.hasCameraExif(),
				quality == null ? null : quality.subcategory(), quality == null ? null : quality.width(),
				quality == null ? null : quality.height(), quality == null ? null : quality.dateSource(),
				quality == null ? null : quality.captureDate(), file.modifiedAt());
	}

	private DuplicateCandidateFileResponse toCandidateFile(DuplicateFileResponse file, Decision decision,
			MediaQuality quality) {
		return new DuplicateCandidateFileResponse(file.id(), file.fileName(), file.extension(), file.fileType(),
				file.size(), file.currentPath(), file.currentFolder(), file.modifiedAt(),
				decision != null ? decision.verdict() : null, decision != null ? decision.reason() : null,
				quality == null ? null : quality.width(), quality == null ? null : quality.height(),
				quality == null ? null : quality.captureDate(), quality == null ? null : quality.dateSource());
	}
}