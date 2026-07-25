package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.DuplicateFileExclusion;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.DuplicateFolderExclusion;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.DuplicateFileExclusionRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.DuplicateFolderExclusionRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateFileExclusionView;
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

	public DuplicateExclusionService(DuplicateFileExclusionRepository fileRepository,
			DuplicateFolderExclusionRepository folderRepository) {
		this.fileRepository = fileRepository;
		this.folderRepository = folderRepository;
	}

	/** @return true when a new exclusion was created, false if already excluded. */
	@Transactional
	public boolean excludeFile(UUID publicId) {
		if (publicId == null || fileRepository.existsByPublicId(publicId)) {
			return false;
		}

		fileRepository.save(DuplicateFileExclusion.builder().publicId(publicId).build());

		return true;
	}

	/**
	 * Stores the folder path in a single, separator-agnostic form: the same absolute
	 * normalization used for {@code catalog_file_location.current_folder} (which drops
	 * any trailing separator), but with every separator forced to {@code /}. The
	 * duplicate queries compare against {@code REPLACE(current_folder, '\', '/')}, so
	 * a folder excluded on Windows (back-slashes) still matches its files and,
	 * crucially, its subfolders.
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

		return true;
	}

	@Transactional(readOnly = true)
	public List<UUID> excludedFilePublicIds() {
		return fileRepository.findAllPublicIds();
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

	@Transactional
	public void removeFileExclusion(Long id) {
		fileRepository.deleteById(id);
	}

	@Transactional
	public void removeFolderExclusion(Long id) {
		folderRepository.deleteById(id);
	}
}