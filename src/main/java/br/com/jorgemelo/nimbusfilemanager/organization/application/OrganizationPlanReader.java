package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationItem;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationSummary;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.StoredPlanPage;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.model.OrganizationPlanItemRecord;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.model.OrganizationPlanRecord;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.OrganizationPlanItemRepository;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.OrganizationPlanRepository;

/**
 * Reading a published plan, one page at a time.
 *
 * <p>
 * This is the whole of what the application does with a plan now. It does not
 * compute one, does not hold one and does not keep one between requests: a page
 * is fifty rows out of the database, so the process serving the screen never
 * carries the other ninety-nine thousand nine hundred and fifty.
 *
 * <p>
 * A plan that is missing, still building, failed or past its expiry all answer
 * the same way - empty - because they are the same answer to the screen: there
 * is nothing here to look at, ask for a new preview. Distinguishing them would
 * be offering the user a difference they cannot act on.
 */
@Service
@Transactional(readOnly = true)
public class OrganizationPlanReader {

	private final OrganizationPlanRepository planRepository;
	private final OrganizationPlanItemRepository itemRepository;
	private final CatalogSignature catalogSignature;
	private final Clock clock;

	public OrganizationPlanReader(OrganizationPlanRepository planRepository,
			OrganizationPlanItemRepository itemRepository, CatalogSignature catalogSignature, Clock clock) {
		this.planRepository = planRepository;
		this.itemRepository = itemRepository;
		this.catalogSignature = catalogSignature;
		this.clock = clock;
	}

	public Optional<StoredPlanPage> page(Long executionId, int page, int size, boolean onlyConflicts) {
		Optional<OrganizationPlanRecord> found = planRepository.findReadable(executionId, LocalDateTime.now(clock));

		if (found.isEmpty()) {
			return Optional.empty();
		}

		OrganizationPlanRecord plan = found.get();

		int totalItems = onlyConflicts ? plan.getConflictCount() : plan.getItemCount();
		int totalPages = totalItems == 0 ? 1 : (int) Math.ceil((double) totalItems / size);
		int safePage = Math.clamp(page, 0, totalPages - 1);

		Pageable pageable = PageRequest.of(safePage, size);

		List<OrganizationPlanItemRecord> rows = onlyConflicts
				? itemRepository.findConflicts(executionId, pageable)
				: itemRepository.findByExecutionIdOrderByOrdinalAsc(executionId, pageable);

		return Optional.of(new StoredPlanPage(plan.getSourcePath(), plan.getTargetPath(), plan.getLayout(),
				summaryOf(plan), catalogChanged(plan), rows.stream().map(this::toItem).toList(), safePage, size,
				totalItems));
	}

	/**
	 * Whether the library moved since this plan was built.
	 *
	 * <p>
	 * A plan built before the signature existed reports unchanged rather than
	 * changed: an old plan is not evidence that anything moved, and warning about
	 * every one of them would teach the user to ignore the warning.
	 */
	private boolean catalogChanged(OrganizationPlanRecord plan) {
		if (plan.getCatalogSignature() == null) {
			return false;
		}

		return !plan.getCatalogSignature().equals(catalogSignature.of(plan.getSourcePath()));
	}

	/**
	 * The counts the screen shows.
	 *
	 * <p>
	 * Four are stored and "already organized" is derived from two of them - an item
	 * the plan does not move is an item already where it belongs - so the column
	 * that would repeat it does not exist. The three that stay zero were
	 * intermediate arithmetic of the planning that no screen renders off a stored
	 * plan.
	 */
	private OrganizationSummary summaryOf(OrganizationPlanRecord plan) {
		int alreadyOrganized = plan.getItemCount() - plan.getPlannedMoves();

		return new OrganizationSummary(plan.getItemCount(), 0, 0, alreadyOrganized, plan.getPlannedMoves(),
				plan.getTotalSizeBytes(), plan.getConflictCount(), 0, 0);
	}

	private OrganizationItem toItem(OrganizationPlanItemRecord row) {
		return new OrganizationItem(null, row.getCatalogFileId(), row.getFileName(), row.getSourcePath(),
				row.getTargetPath(), null, null, null, null, null, null, null, row.getSizeBytes(), false, false, false,
				false, row.isConflict(), row.getConflictType(), row.getLocation(), row.getLocationConfidence());
	}
}