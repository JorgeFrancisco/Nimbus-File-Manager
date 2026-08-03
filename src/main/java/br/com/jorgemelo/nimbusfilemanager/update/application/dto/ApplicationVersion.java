package br.com.jorgemelo.nimbusfilemanager.update.application.dto;

/**
 * A version of this application, split into the four numbers of
 * {@code MAJOR.MINOR.PATCH.BUILD}.
 *
 * <p>
 * The build is carried but never decides anything - see
 * {@code ApplicationVersions} for why the installer cannot see it.
 *
 * @param major incompatible or deeply architectural change
 * @param minor compatible new functionality
 * @param patch bug fix or small improvement
 * @param build ever-increasing counter, zero when the text carried only three
 * numbers
 */
public record ApplicationVersion(int major, int minor, int patch, int build) {
}