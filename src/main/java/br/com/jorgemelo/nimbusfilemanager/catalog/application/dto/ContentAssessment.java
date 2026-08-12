package br.com.jorgemelo.nimbusfilemanager.catalog.application.dto;

import br.com.jorgemelo.nimbusfilemanager.catalog.domain.enums.ContentVerdict;

/**
 * The two things a look at a file settles, kept apart because they are two
 * questions.
 *
 * @param verdict what happened to the bytes
 * @param physicallyReplaced whether the object at that path is a different one
 * from the object the catalog recorded. It is not, by itself, a statement about
 * content: an application that saves by writing a temporary file and swapping it
 * in replaces the object every time it saves, and whether the bytes differ is
 * still the digest's to answer
 */
public record ContentAssessment(ContentVerdict verdict, boolean physicallyReplaced) {
}