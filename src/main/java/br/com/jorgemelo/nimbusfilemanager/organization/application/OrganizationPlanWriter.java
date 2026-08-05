package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationItem;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPlan;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.PlanStatus;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.model.OrganizationPlanItemRecord;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.model.OrganizationPlanRecord;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.OrganizationPlanItemRepository;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.OrganizationPlanRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.OrganizationPlanProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * Writes a computed plan where another process can read it.
 *
 * <p>
 * Split the same way the similarity publication is, and for the same reason: a
 * long transaction writes rows nobody can see, and a short one makes them the
 * answer. Between the two there is no moment at which the screen shows part of
 * a plan, because the reading filters on the status the short transaction sets.
 *
 * <p>
 * This is worker-side code. The application reads plans; it does not produce
 * them.
 */
@Slf4j
@Component
class OrganizationPlanWriter {

	/** Rows per insert, so a plan at the item cap is not one huge statement. */
	private static final int BATCH_SIZE = 500;

	private final OrganizationPlanRepository planRepository;
	private final OrganizationPlanItemRepository itemRepository;
	private final CatalogSignature catalogSignature;
	private final OrganizationPlanProperties planProperties;
	private final Clock clock;

	OrganizationPlanWriter(OrganizationPlanRepository planRepository, OrganizationPlanItemRepository itemRepository,
			CatalogSignature catalogSignature, OrganizationPlanProperties planProperties, Clock clock) {
		this.planRepository = planRepository;
		this.itemRepository = itemRepository;
		this.catalogSignature = catalogSignature;
		this.planProperties = planProperties;
		this.clock = clock;
	}

	/**
	 * Opens the plan and writes every item, all of it invisible.
	 *
	 * <p>
	 * The expiry is decided here, when the plan is born, rather than when it is
	 * published: a run that dies before publishing leaves a row that expires on the
	 * same schedule as any other, so the residue is swept by the rule that already
	 * exists instead of one written for it.
	 */
	@Transactional
	void build(Long executionId, OrganizationPlan plan) {
		LocalDateTime now = LocalDateTime.now(clock);

		planRepository.save(OrganizationPlanRecord.builder().executionId(executionId).sourcePath(plan.sourcePath())
				.targetPath(plan.targetPath()).layout(plan.layout()).status(PlanStatus.BUILDING).itemCount(0)
				.conflictCount(0).plannedMoves(0).totalSizeBytes(0L)
				.expiresAt(now.plusHours(planProperties.ttlHoursOrDefault())).build());

		writeItems(executionId, plan.items());
	}

	/**
	 * The publication itself: one update, in its own short transaction.
	 *
	 * @return whether this call was the one that published. A {@code false} means
	 * the row was no longer BUILDING - somebody else already decided its fate - and
	 * the caller reports that rather than assuming it won
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	boolean publish(Long executionId, OrganizationPlan plan) {
		int published = planRepository.publish(executionId, plan.items().size(), (int) plan.summary().conflicts(),
				(int) plan.summary().plannedMoves(), plan.summary().totalSizeBytes(),
				catalogSignature.of(plan.sourcePath()), LocalDateTime.now(clock));

		if (published == 0) {
			log.warn("Organization plan {} was no longer building and was not published", executionId);

			return false;
		}

		return true;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void markFailed(Long executionId) {
		planRepository.markFailed(executionId);
	}

	private void writeItems(Long executionId, List<OrganizationItem> items) {
		List<OrganizationPlanItemRecord> batch = new ArrayList<>(BATCH_SIZE);

		for (int ordinal = 0; ordinal < items.size(); ordinal++) {
			batch.add(rowOf(executionId, ordinal, items.get(ordinal)));

			if (batch.size() == BATCH_SIZE) {
				itemRepository.saveAll(batch);
				batch.clear();
			}
		}

		if (!batch.isEmpty()) {
			itemRepository.saveAll(batch);
		}
	}

	private OrganizationPlanItemRecord rowOf(Long executionId, int ordinal, OrganizationItem item) {
		return OrganizationPlanItemRecord.builder().executionId(executionId).ordinal(ordinal)
				.catalogFileId(item.catalogFileId()).fileName(item.fileName()).sourcePath(item.sourcePath())
				.targetPath(item.targetPath()).sizeBytes(item.sizeBytes()).location(item.location())
				.locationConfidence(item.locationConfidence()).conflict(item.conflict())
				.conflictType(item.conflictType()).build();
	}
}