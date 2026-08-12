package br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection;

/**
 * What the catalog holds about one file's bytes, carrying back the path it was
 * asked about so a batch of answers can be matched to the batch of questions.
 */
public interface KnownContentBatchRow extends KnownContentRow {

	String getInputPath();
}