package br.com.jorgemelo.nimbusfilemanager.shared.application.constants;

/**
 * The folder each external tool is installed into, under the workspace's
 * {@code tools}. Shared because more than one class has to agree on them: the
 * cluster and the dump tools both live in the PostgreSQL one.
 */
public final class ToolFolders {

	public static final String POSTGRESQL = "postgresql";
	public static final String FFMPEG = "ffmpeg";

	private ToolFolders() {
	}
}