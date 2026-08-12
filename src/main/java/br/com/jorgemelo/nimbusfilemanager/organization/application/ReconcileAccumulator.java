package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentSuspect;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MissingFile;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileIssueResponse;

/**
 * Package-private holder that tallies reconcile findings (missing on disk,
 * missing in database) and keeps bounded samples of each, alongside the full
 * lists a repair pass works from. Used only by
 * {@link OrganizationReconcileService} while scanning a source tree.
 */
class ReconcileAccumulator {

	private final int sampleLimit;
	private final Set<String> dbPaths = new HashSet<>();
	/** Every catalogued file the pass found gone, not a sample of them. */
	private final ArrayList<MissingFile> missingFiles = new ArrayList<>();

	/** Every present file whose size or timestamp no longer matches the catalog. */
	private final ArrayList<ContentSuspect> contentSuspects = new ArrayList<>();

	/** Every path on disk with no catalogued file, not a sample of them. */
	private final ArrayList<String> physicalOnly = new ArrayList<>();

	private final ArrayList<OrganizationReconcileIssueResponse> missingOnDiskSamples = new ArrayList<>();
	private final ArrayList<OrganizationReconcileIssueResponse> missingInDatabaseSamples = new ArrayList<>();
	private long missingOnDisk;
	private long missingInDatabase;

	ReconcileAccumulator(int sampleLimit) {
		this.sampleLimit = sampleLimit;
	}

	void addDatabasePath(String path) {
		dbPaths.add(path);
	}

	void addMissingOnDisk(Long catalogFileId, String currentPath) {
		missingOnDisk++;

		// Two collections and they are not the same thing. The sample is what a screen
		// shows and stops at a hundred; this is every file the pass has to converge,
		// and capping it was how a run could report five thousand differences and
		// repair a hundred of them.
		missingFiles.add(new MissingFile(catalogFileId, currentPath));

		addSample(missingOnDiskSamples, new OrganizationReconcileIssueResponse(catalogFileId, currentPath, currentPath,
				null, "File is registered in database but does not exist on disk."));
	}

	void addMissingInDatabase(String path) {
		missingInDatabase++;

		physicalOnly.add(path);

		addSample(missingInDatabaseSamples, new OrganizationReconcileIssueResponse(null, path, null, path,
				"File exists on disk but is not registered in database."));
	}

	void addContentSuspect(Long catalogFileId, String currentPath) {
		contentSuspects.add(new ContentSuspect(catalogFileId, currentPath));
	}

	List<ContentSuspect> contentSuspects() {
		return contentSuspects;
	}

	List<MissingFile> missingFiles() {
		return missingFiles;
	}

	List<String> physicalOnly() {
		return physicalOnly;
	}

	private void addSample(List<OrganizationReconcileIssueResponse> samples,
			OrganizationReconcileIssueResponse sample) {
		if (samples.size() < sampleLimit) {
			samples.add(sample);
		}
	}

	Set<String> dbPaths() {
		return dbPaths;
	}

	long filesInDatabase() {
		return dbPaths.size();
	}

	long missingOnDisk() {
		return missingOnDisk;
	}

	long missingInDatabase() {
		return missingInDatabase;
	}

	List<OrganizationReconcileIssueResponse> missingOnDiskSamples() {
		return missingOnDiskSamples;
	}

	List<OrganizationReconcileIssueResponse> missingInDatabaseSamples() {
		return missingInDatabaseSamples;
	}
}