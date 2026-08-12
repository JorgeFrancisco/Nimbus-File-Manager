package br.com.jorgemelo.nimbusfilemanager.shared.application;

import java.io.IOException;

/**
 * A change this product makes to the file system, run between the announcement
 * of the paths it touches and the settling of that announcement.
 *
 * <p>
 * It exists because the announcement has a lifecycle and the lifecycle has to
 * be impossible to get half right: a write that fails must take its
 * announcement with it, and one that succeeds must stop being tied to the
 * execution that made it. Handing the effect to the registry is what makes both
 * ends structural rather than something each caller remembers.
 */
@FunctionalInterface
public interface SelfWriteAction {

	void run() throws IOException;
}