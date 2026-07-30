package br.com.jorgemelo.nimbusfilemanager.metadata.application.dto;

import java.util.List;

/**
 * What a dry run learned beyond the candidate count, which on its own never
 * answered the two questions the screen is actually asked: how much of the
 * folder the "continue where it stopped" cutoff is hiding, and what the run
 * would change. Only a sample is examined - reading every file would cost the
 * same as the real run and defeat the point of simulating.
 *
 * @param skippedByCutoff files inside the folder that the cutoff leaves out.
 * @param examined        files whose metadata was extracted for this preview.
 * @param wouldChange     how many of those would end up with another date.
 * @param preview         the first differences, to show on screen.
 */
public record MetadataRebuildSimulation(int skippedByCutoff, int examined, int wouldChange,
		List<MetadataRebuildPreview> preview) {
}