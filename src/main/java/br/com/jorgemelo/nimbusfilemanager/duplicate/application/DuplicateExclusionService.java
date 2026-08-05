package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.DuplicateFileExclusion;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.DuplicateFolderExclusion;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.DuplicateFileExclusionRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.DuplicateFolderExclusionRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateFileExclusionView;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.EligibilityChanged;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Owns the "hide from duplicate comparison" lists: single files (by stable
 * public id) and whole folders (by normalized path, recursive). The files stay
 * fully inventoried - they are only dropped from the Duplicados screen. The
 * duplicate queries filter against these tables directly; this service is the
 * write side plus the read side the similar tab and the management screen need.
 */
@Service
public class DuplicateExclusionService {

	private final DuplicateFileExclusionRepository fileRepository;
	private final DuplicateFolderExclusionRepository folderRepository;
	private final ApplicationEventPublisher eventPublisher;

	public DuplicateExclusionService(DuplicateFileExclusionRepository fileRepository,
			DuplicateFolderExclusionRepository folderRepository, ApplicationEventPublisher eventPublisher) {
		this.fileRepository = fileRepository;
		this.folderRepository = folderRepository;
		this.eventPublisher = eventPublisher;
	}

	/** @return true when a new exclusion was created, false if already excluded. */
	@Transactional
	public boolean excludeFile(UUID publicId) {
		if (publicId == null || fileRepository.existsByPublicId(publicId)) {
			return false;
		}

		fileRepository.save(DuplicateFileExclusion.builder().publicId(publicId).build());

		eventPublisher.publishEvent(new EligibilityChanged("file exclusion"));

		return true;
	}

	/**
	 * Stores the folder path in a single, separator-agnostic form: the same
	 * absolute normalization used for {@code catalog_file_location.current_folder}
	 * (which drops any trailing separator), but with every separator forced to
	 * {@code /}. The duplicate queries compare against
	 * {@code REPLACE(current_folder, '\', '/')}, so a folder excluded on Windows
	 * (back-slashes) still matches its files and, crucially, its subfolders.
	 *
	 * @return true when a new exclusion was created, false if already excluded.
	 */
	@Transactional
	public boolean excludeFolder(String folderPath) {
		if (folderPath == null || folderPath.isBlank()) {
			return false;
		}

		String normalized = PathUtils.normalize(folderPath).replace('\\', '/');

		if (folderRepository.existsByFolderPath(normalized)) {
			return false;
		}

		folderRepository.save(DuplicateFolderExclusion.builder().folderPath(normalized).build());

		eventPublisher.publishEvent(new EligibilityChanged("folder exclusion"));

		return true;
	}

	@Transactional(readOnly = true)
	public List<UUID> excludedFilePublicIds() {
		return fileRepository.findAllPublicIds();
	}

	/**
	 * A deterministic fingerprint of both lists, so a similarity result computed
	 * before the user changed one of them is never reused as if it still applied.
	 *
	 * <p>
	 * Saying "these two are not duplicates" changes what the analysis is allowed to
	 * find, which makes it a parameter of the analysis in every sense that matters.
	 * Until now it was invisible to the key: the cache was cleared by hand from the
	 * controller, which worked only because the cache lived in the same process as
	 * the click.
	 */
	@Transactional(readOnly = true)
	public String signature() {
		List<String> files = fileRepository.findAllPublicIds().stream().map(UUID::toString).sorted().toList();

		List<String> folders = folderRepository.findAllFolderPaths().stream().sorted().toList();

		return SimilarityDigest.ofExclusions(files, folders);
	}

	@Transactional(readOnly = true)
	public List<String> excludedFolders() {
		return folderRepository.findAllFolderPaths();
	}

	/**
	 * Whether a candidate's current folder sits at or under one of the excluded
	 * folders. Excluded folders are stored separator-agnostic (forward slashes) and
	 * a candidate's current folder is OS-native, so it is normalized the same way
	 * before matching the folder itself or any subfolder. The folder list is passed
	 * in (from {@link #excludedFolders()}) so a batch check queries it just once.
	 */
	boolean isUnderExcludedFolder(String folder, List<String> excludedFolders) {
		if (folder == null) {
			return false;
		}

		String normalized = folder.replace('\\', '/');

		for (String excluded : excludedFolders) {
			if (normalized.equals(excluded) || normalized.startsWith(excluded + "/")) {
				return true;
			}
		}

		return false;
	}

	@Transactional(readOnly = true)
	public List<DuplicateFileExclusionView> fileExclusions() {
		return fileRepository.findAllViews();
	}

	@Transactional(readOnly = true)
	public List<DuplicateFolderExclusion> folderExclusions() {
		return folderRepository.findAll();
	}

	/**
	 * Lifting an exclusion is the case the stored relations were kept for: the
	 * files come back to the analysed set and what was already computed about them
	 * is still true. Announced only when a row really went, so that deleting an id
	 * twice asks for one regroup rather than two.
	 */
	@Transactional
	public void removeFileExclusion(Long id) {
		if (!fileRepository.existsById(id)) {
			return;
		}

		fileRepository.deleteById(id);

		eventPublisher.publishEvent(new EligibilityChanged("file exclusion lifted"));
	}

	@Transactional
	public void removeFolderExclusion(Long id) {
		if (!folderRepository.existsById(id)) {
			return;
		}

		folderRepository.deleteById(id);

		eventPublisher.publishEvent(new EligibilityChanged("folder exclusion lifted"));
	}
}