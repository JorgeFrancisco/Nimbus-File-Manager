package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.DuplicateExclusionFile;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.DuplicateFolderExclusion;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.DuplicateExclusionFileRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.DuplicateFolderExclusionRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateExclusionFileView;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.EligibilityChanged;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
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

	private final DuplicateExclusionFileRepository fileRepository;
	private final CatalogFileRepository catalogFileRepository;
	private final DuplicateFolderExclusionRepository folderRepository;
	private final ApplicationEventPublisher eventPublisher;

	public DuplicateExclusionService(DuplicateExclusionFileRepository fileRepository,
			CatalogFileRepository catalogFileRepository,
			DuplicateFolderExclusionRepository folderRepository, ApplicationEventPublisher eventPublisher) {
		this.fileRepository = fileRepository;
		this.folderRepository = folderRepository;
		this.catalogFileRepository = catalogFileRepository;
		this.eventPublisher = eventPublisher;
	}

	/**
	 * Records that the user does not want this file offered as a duplicate, and
	 * what content they said it about.
	 *
	 * <p>
	 * The revision is read here, at the moment of the decision, because that is
	 * what the decision was about. Replace the bytes later and the row stays -
	 * the user did say it - but it stops applying to a picture they never saw.
	 *
	 * <p>
	 * Which is why saying it again has to be able to mean something. Once the
	 * content has moved on the file is back on the Duplicados screen, and the user
	 * clicking there is looking at a different picture: that is a new judgement,
	 * and it replaces the one about the bytes that are gone. Refusing it merely
	 * because a row exists would leave the button doing nothing at all, for as
	 * long as the file exists, with nothing on screen to say why.
	 *
	 * @return true when a judgement was written, false when the same one was
	 * already on record - nothing changed, so nothing is announced
	 */
	@Transactional
	public boolean excludeFile(UUID publicId) {
		if (publicId == null) {
			return false;
		}

		CatalogFile catalogFile = catalogFileRepository.findByCatalogFilePublicId(publicId).orElse(null);

		if (catalogFile == null) {
			return false;
		}

		DuplicateExclusionFile said = fileRepository.findByCatalogFileId(catalogFile.getId()).orElse(null);

		if (said != null) {
			if (said.getContentRevision().equals(catalogFile.getContentRevision())) {
				return false;
			}

			supersede(said);
		}

		fileRepository.save(DuplicateExclusionFile.builder().catalogFile(catalogFile)
				.contentRevision(catalogFile.getContentRevision()).build());

		eventPublisher.publishEvent(new EligibilityChanged("file exclusion"));

		return true;
	}

	/**
	 * Clears the way for a judgement about the content that is there now.
	 *
	 * <p>
	 * The standing row goes rather than being corrected: what it says is not
	 * updatable, because a judgement about other bytes is another judgement, made
	 * at another time. Flushed so the delete reaches the database before the insert
	 * that follows it - Hibernate orders inserts first, and one judgement per file
	 * is what the table enforces, so the second one would be refused.
	 */
	private void supersede(DuplicateExclusionFile said) {
		fileRepository.delete(said);

		fileRepository.flush();
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
		return fileRepository.findApplyingCatalogFilePublicIds();
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
		List<String> files = fileRepository.findApplyingCatalogFilePublicIds().stream().map(UUID::toString)
				.sorted().toList();

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
	public List<DuplicateExclusionFileView> fileExclusions() {
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