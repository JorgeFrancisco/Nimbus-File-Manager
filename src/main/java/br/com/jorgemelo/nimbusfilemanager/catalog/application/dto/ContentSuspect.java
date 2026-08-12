package br.com.jorgemelo.nimbusfilemanager.catalog.application.dto;

/**
 * A catalogued file still where the catalog says, whose cheap facts moved - and
 * the place it sits.
 *
 * <p>
 * Lives with the verification it asks for rather than with either of the two
 * passes that notice it: a reconcile comparing the catalog against the disk,
 * and an inventory finding a file whose size or timestamp moved. Both detect
 * the same thing and both ask for the same work.
 *
 * <p>
 * The path travels with the identifier, and the reason is not symmetry: the
 * verification this asks for is an execution, executions of this type are
 * excluded by path, and a row that names no path cannot take the lock it needs.
 * Asking with the identifier alone produced a request that could never run, and
 * a divergence that could never converge - so the next pass found it again, and
 * the one after that.
 *
 * <p>
 * It is carried rather than looked up because the pass already has it: the row
 * that reveals the divergence is the row that says where the file is, and going
 * back to the database for something already in hand would be one query per
 * suspect.
 */
public record ContentSuspect(Long catalogFileId, String currentPath) {
}