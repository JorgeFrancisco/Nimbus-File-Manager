package br.com.jorgemelo.nimbusfilemanager.settings.application;

import java.util.Optional;

/**
 * Answers whether a tool actually runs from a given path. A port because the
 * only honest answer comes from spawning the process - a file that exists may
 * still be the wrong architecture or miss its DLLs, and a bare command only
 * works if PATH resolves it.
 */
public interface ExternalToolProbe {

	/** The reported version, or empty when the tool does not run. */
	Optional<String> version(String executable);
}