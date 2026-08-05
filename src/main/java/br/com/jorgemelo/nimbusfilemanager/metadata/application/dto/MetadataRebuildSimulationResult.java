package br.com.jorgemelo.nimbusfilemanager.metadata.application.dto;

import java.util.List;

/**
 * What a dry run found, before anything is worded or stored.
 *
 * <p>
 * The screen's version of the same thing is {@link MetadataRebuildSimulation},
 * which the reader builds out of the stored rows.
 *
 * @param candidates files the request would touch, within its own limit.
 * @param skippedByCutoff files inside the folder that the cutoff leaves out.
 * @param examined files whose metadata was extracted for this preview.
 * @param wouldChange how many of those would end up with another date.
 * @param differences the first of them, as they will be listed.
 */
public record MetadataRebuildSimulationResult(int candidates, int skippedByCutoff, int examined, int wouldChange,
		List<MetadataDateDifference> differences) {
}