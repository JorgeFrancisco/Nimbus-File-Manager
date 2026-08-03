package br.com.jorgemelo.nimbusfilemanager.update.application;

import java.util.Optional;

import br.com.jorgemelo.nimbusfilemanager.update.application.dto.PublishedRelease;

/**
 * Where published releases are asked about.
 *
 * <p>
 * A port because this is the one thing in the update check that leaves the
 * machine, and because what decides whether to offer an update must be testable
 * without a network - the interesting cases are an installation that is already
 * current, one that is behind, and a release nobody can parse, none of which
 * should need a server to exercise.
 */
public interface ReleaseSource {

	/**
	 * @return the most recent published release, or empty when there is none,
	 * when the machine is offline, or when the answer could not be understood -
	 * all of which mean the same thing here: nothing to offer right now
	 */
	Optional<PublishedRelease> latest();
}