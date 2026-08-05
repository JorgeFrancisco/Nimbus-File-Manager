package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Reason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Verdict;

/**
 * One file of an analysed group, reduced to what has to survive the analysis:
 * which file, what was decided about it, and why.
 *
 * <p>
 * Name, size, path and thumbnail are deliberately absent. They belong to the
 * catalog and are read when the screen renders, so a file renamed or moved after
 * publication shows where it is now rather than where it was - and a result that
 * carried copies of them would be a second, silently ageing catalog.
 */
public record AnalyzedMember(UUID mediaPublicId, Verdict verdict, Reason reason) {
}