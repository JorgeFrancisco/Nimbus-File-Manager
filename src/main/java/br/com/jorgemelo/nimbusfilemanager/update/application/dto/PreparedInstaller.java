package br.com.jorgemelo.nimbusfilemanager.update.application.dto;

import java.nio.file.Path;

import br.com.jorgemelo.nimbusfilemanager.update.domain.enums.UpdateOutcome;

/**
 * The result of fetching and verifying an installer, before anything is run.
 *
 * <p>
 * Exactly one of the two is present: a file that passed verification, or the
 * reason it will not be run.
 *
 * @param installer the verified installer, or {@code null} when it was refused
 * @param refusal why it will not be run, or {@code null} when it is ready
 */
public record PreparedInstaller(Path installer, UpdateOutcome refusal) {
}