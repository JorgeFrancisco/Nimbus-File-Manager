package br.com.jorgemelo.nimbusfilemanager.settings.application.dto;

/**
 * Everything a worker needs to carry out a library switch, written down.
 *
 * <p>
 * The two folders are here <strong>by name</strong> and that is the point. A
 * queued switch can be claimed minutes later, by another process, after another
 * switch has already happened - so the one thing it must never do is ask what
 * the current library is. It forgets the library this payload names, and sets
 * the one this payload names, whatever the setting says by then.
 *
 * <p>
 * The folders are not columns of the execution because they already are: the row
 * carries them as source and target, which is what the worker locks. They are
 * repeated here for nothing, so they are not repeated here at all - only what
 * the columns cannot hold lives in the payload.
 *
 * @param username who asked, so the setting change is attributed to a person
 * rather than to a background process
 */
public record LibrarySwitchPayload(Integer schemaVersion, String username) {
}